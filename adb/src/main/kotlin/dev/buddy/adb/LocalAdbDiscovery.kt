package dev.buddy.adb

import android.content.Context
import android.content.pm.PackageManager
import android.net.Network
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dev.buddy.core.LoopbackPort
import java.io.IOException
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class LocalAdbDiscovery(context: Context) {
    private val context = context.applicationContext

    suspend fun find(kind: AdbServiceKind, serviceNameHint: String? = null): List<LoopbackPort> =
        withContext(Dispatchers.IO) {
            require(serviceNameHint == null || serviceNameHint.length in 1..128)
            if (Build.VERSION.SDK_INT >= 37 &&
                context.checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Local network permission required")
            }
            val localAddresses = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap {
                it.inetAddresses.toList()
            }.toSet()
            val manager = context.getSystemService(NsdManager::class.java)
            withTimeoutOrNull(8_000) {
                callbackFlow {
                    val ended = AtomicBoolean()
                    val lock = Any()
                    val names = mutableSetOf<String>()
                    val subscriptions = mutableListOf<NsdManager.ServiceInfoCallback>()
                    val candidates = mutableMapOf<String, LoopbackPort>()
                    fun publish(key: String, info: NsdServiceInfo?) {
                        if (ended.get()) return
                        val ports = synchronized(lock) {
                            val addresses = info?.let(::addresses).orEmpty()
                            if (info != null &&
                                LocalPeerPolicy.accepts(
                                    kind,
                                    info.serviceType.orEmpty(),
                                    info.port,
                                    addresses,
                                    localAddresses
                                )
                            ) {
                                candidates[key] = LoopbackPort(info.port)
                            } else {
                                candidates.remove(key)
                            }
                            candidates.values.distinct().sortedBy { it.value }
                        }
                        trySend(ports)
                    }
                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(type: String) = Unit
                        override fun onDiscoveryStopped(type: String) = Unit
                        override fun onStartDiscoveryFailed(type: String, error: Int) {
                            close(IOException("Local discovery unavailable"))
                        }
                        override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
                        override fun onServiceLost(info: NsdServiceInfo) {
                            publish(info.serviceName, null)
                        }
                        override fun onServiceFound(info: NsdServiceInfo) {
                            val name = info.serviceName ?: return
                            if (ended.get() || name.length !in 1..128 ||
                                !info.serviceType.orEmpty().removeSuffix(
                                    "."
                                ).equals(kind.serviceType, ignoreCase = true) ||
                                (serviceNameHint != null && name != serviceNameHint)
                            ) {
                                return
                            }
                            synchronized(lock) {
                                if (names.size >= 16 || !names.add(name) || ended.get()) return
                                if (Build.VERSION.SDK_INT >= 34) {
                                    val callback = object : NsdManager.ServiceInfoCallback {
                                        override fun onServiceInfoCallbackRegistrationFailed(error: Int) {
                                            publish(name, null)
                                        }
                                        override fun onServiceInfoCallbackUnregistered() = Unit
                                        override fun onServiceLost() {
                                            publish(name, null)
                                        }
                                        override fun onServiceUpdated(updated: NsdServiceInfo) {
                                            publish(name, updated)
                                        }
                                    }
                                    subscriptions += callback
                                    runCatching {
                                        manager.registerServiceInfoCallback(info, context.mainExecutor, callback)
                                    }
                                } else {
                                    legacyResolve(manager, info) { resolved -> publish(name, resolved) }
                                }
                            }
                        }
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= 35) {
                            manager.discoverServices(
                                DiscoveryRequest.Builder(kind.serviceType).build(),
                                context.mainExecutor,
                                listener
                            )
                        } else {
                            manager.discoverServices(
                                kind.serviceType,
                                NsdManager.PROTOCOL_DNS_SD,
                                null as Network?,
                                context.mainExecutor,
                                listener
                            )
                        }
                    } catch (_: Exception) {
                        close(IOException("Local discovery unavailable"))
                    }
                    awaitClose {
                        ended.set(true)
                        runCatching { manager.stopServiceDiscovery(listener) }
                        synchronized(lock) {
                            if (Build.VERSION.SDK_INT >= 34) {
                                subscriptions.forEach {
                                    runCatching { manager.unregisterServiceInfoCallback(it) }
                                }
                            }
                            subscriptions.clear()
                            candidates.clear()
                        }
                    }
                }.first { it.isNotEmpty() }
            }.orEmpty()
        }

    @Suppress("DEPRECATION")
    private fun addresses(info: NsdServiceInfo) =
        if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)

    @Suppress("DEPRECATION")
    private fun legacyResolve(manager: NsdManager, info: NsdServiceInfo, result: (NsdServiceInfo?) -> Unit) {
        runCatching {
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, error: Int) {
                        result(null)
                    }
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        result(service)
                    }
                }
            )
        }.onFailure { result(null) }
    }
}

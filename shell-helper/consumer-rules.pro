# These fixed entry points are invoked by app_process rather than application code.
-keep class dev.buddy.helper.HelperMain {
    public static void main(java.lang.String[]);
}
-keep class dev.buddy.helper.RevalidateMain {
    public static void main(java.lang.String[]);
}

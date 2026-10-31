(function(root) {
  'use strict';
  const doc = root.document;
  const uncurry = fn => Function.prototype.call.bind(fn);
  const descriptor = Object.getOwnPropertyDescriptor;
  const create = Object.create;
  const serialize = JSON.stringify;
  const max = Math.max;
  const item = uncurry(HTMLCollection.prototype.item);
  const count = uncurry(descriptor(HTMLCollection.prototype, 'length').get);
  const all = uncurry(Document.prototype.getElementsByTagName);
  const name = uncurry(descriptor(Element.prototype, 'localName').get);
  const attr = uncurry(Element.prototype.getAttribute);
  const parent = uncurry(descriptor(Node.prototype, 'parentElement').get);
  const value = uncurry(descriptor(Node.prototype, 'nodeValue').get);
  const body = uncurry(descriptor(Document.prototype, 'body').get);
  const title = uncurry(descriptor(Document.prototype, 'title').get);
  const visibility = uncurry(descriptor(Document.prototype, 'visibilityState').get);
  const walker = uncurry(Document.prototype.createTreeWalker);
  const next = uncurry(TreeWalker.prototype.nextNode);
  const style = root.getComputedStyle.bind(root);
  const property = uncurry(CSSStyleDeclaration.prototype.getPropertyValue);
  const rect = uncurry(Element.prototype.getBoundingClientRect);
  const width = uncurry(descriptor(DOMRectReadOnly.prototype, 'width').get);
  const height = uncurry(descriptor(DOMRectReadOnly.prototype, 'height').get);
  const slice = uncurry(String.prototype.slice);
  const lower = uncurry(String.prototype.toLowerCase);
  const trim = uncurry(String.prototype.trim);
  const index = uncurry(String.prototype.indexOf);
  const charAt = uncurry(String.prototype.charCodeAt);
  const has = uncurry(Set.prototype.has);
  const inputs = new Set(['hidden', 'button', 'submit', 'reset', 'image', 'search']);
  const ignored = new Set(['script', 'style', 'noscript', 'template', 'head']);
  const complex = new Set(['iframe', 'canvas', 'object', 'embed']);
  function clip(text, length) {
    let result = slice(text, 0, length);
    const last = charAt(result, result.length - 1);
    if (last >= 0xD800 && last <= 0xDBFF) result = slice(result, 0, -1);
    return result;
  }
  function visible(element) {
    const css = style(element);
    if (property(css, 'display') === 'none' || property(css, 'visibility') === 'hidden') return false;
    const bounds = rect(element);
    return width(bounds) > 0 && height(bounds) > 0;
  }
  function classHas(text, wanted) {
    let start = 0;
    for (let end = 0; end <= text.length; ++end) {
      if (end === text.length || charAt(text, end) <= 32) {
        if (slice(text, start, end) === wanted) return true;
        start = end + 1;
      }
    }
    return false;
  }
  function observe() {
    if (visibility(doc) !== 'visible') return '{"status":"unsupported"}';
    const elements = all(doc, '*');
    const length = count(elements);
    if (length > 2000) return '{"status":"unsupported"}';
    for (let i = 0; i < length; ++i) {
      const element = item(elements, i);
      const tag = name(element);
      if (index(tag, '-') >= 0) return '{"status":"unsupported"}';
      if (!visible(element)) continue;
      const rawId = attr(element, 'id') || '';
      const rawClasses = attr(element, 'class') || '';
      if (rawId.length > 256 || rawClasses.length > 4096) return '{"status":"unsupported"}';
      const id = lower(rawId);
      const classes = lower(rawClasses);
      if (id === 'captcha' || id === 'recaptcha' || id === 'hcaptcha' ||
          classHas(classes, 'g-recaptcha') || classHas(classes, 'h-captcha') ||
          classHas(classes, 'cf-turnstile')) return '{"status":"human"}';
      if (has(complex, tag)) return '{"status":"unsupported"}';
      const editable = attr(element, 'contenteditable');
      if (tag === 'textarea' || (editable !== null && lower(editable) !== 'false')) return '{"status":"private"}';
      if (tag === 'input') {
        const type = lower(attr(element, 'type') || 'text');
        const completion = lower(attr(element, 'autocomplete') || '');
        if (!has(inputs, type) || index(completion, 'password') >= 0 || index(completion, 'one-time-code') >= 0 ||
            index(completion, 'cc-') >= 0) return '{"status":"private"}';
      }
    }
    const container = body(doc);
    if (!container) return '{"status":"unsupported"}';
    const walk = walker(doc, container, 4);
    let text = '', node, visited = 0, truncated = false;
    while ((node = next(walk))) {
      if (++visited > 4000) { truncated = true; break; }
      let element = parent(node), visibleText = true, depth = 0;
      while (element) {
        if (++depth > 32 || has(ignored, name(element)) || !visible(element)) { visibleText = false; break; }
        element = parent(element);
      }
      if (!visibleText) continue;
      const raw = value(node) || '';
      const part = trim(clip(raw, 32769));
      if (raw.length > 32769) truncated = true;
      if (!part) continue;
      if (text.length + part.length + 1 > 32768) {
        text += '\n' + clip(part, max(0, 32767 - text.length));
        truncated = true;
        break;
      }
      text += (text ? '\n' : '') + part;
    }
    const result = create(null);
    result.status = 'allowed';
    result.title = clip(title(doc), 256);
    result.text = text;
    result.truncated = truncated;
    return serialize(result);
  }
  Object.defineProperty(root, '__BUDDY_OBSERVER_KEY__', {
    value: observe, writable: false, configurable: false, enumerable: false
  });
})(this);

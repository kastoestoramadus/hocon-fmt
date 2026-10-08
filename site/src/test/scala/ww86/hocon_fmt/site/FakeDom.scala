package ww86.hocon_fmt.site

import scala.scalajs.js

/** The slice of the DOM Laminar needs to mount a component, on Node, without jsdom: nodes, the
  * attribute and child operations Laminar's `DomApi` calls, an event loop's worth of listeners,
  * and the element classes Laminar's `instanceof` checks resolve as globals. Wiring the jsdom npm
  * module into `sbt test` would add a network-time dependency to a build that must not silently
  * skip a platform; this is a plain JS source string instead, linked with the tests themselves.
  *
  * Two things it deliberately does not model, because the components under test never reach for
  * them: HTML parsing (elements are built through `createElement`/`appendChild`) and layout.
  * Event dispatch is simplified where the components never reach either: an event runs only the
  * listeners on its target — no bubbling or capture, and the `addEventListener` options are
  * dropped — while a fired event carries just `type` and `target`, not `preventDefault` or
  * `currentTarget`. The structure it does model — parents, siblings, listeners, attributes — it
  * models the way browsers report it, so a test asserting on the mounted tree reads like the
  * page itself.
  */
object FakeDom {

  val source =
    """(function () {
      |  "use strict";
      |
      |  class Node {
      |    constructor(nodeType, nodeName) {
      |      this.nodeType = nodeType;
      |      this.nodeName = nodeName;
      |      this.tagName = nodeName;
      |      this.data = null;
      |      this.childNodes = [];
      |      this.parentNode = null;
      |      this.attributes = {};
      |      this.listeners = {};
      |      this.style = {
      |        setProperty: function () {},
      |        removeProperty: function () {},
      |        getPropertyValue: function () { return ""; }
      |      };
      |    }
      |    get firstChild() { return this.childNodes[0] || null; }
      |    get lastChild() { return this.childNodes.length ? this.childNodes[this.childNodes.length - 1] : null; }
      |    get nextSibling() {
      |      if (!this.parentNode) return null;
      |      const siblings = this.parentNode.childNodes;
      |      return siblings[siblings.indexOf(this) + 1] || null;
      |    }
      |    get previousSibling() {
      |      if (!this.parentNode) return null;
      |      const siblings = this.parentNode.childNodes;
      |      const index = siblings.indexOf(this);
      |      return index > 0 ? siblings[index - 1] : null;
      |    }
      |    get textContent() {
      |      if (this.nodeType === 3 || this.nodeType === 8) return this.data;
      |      return this.childNodes.map(function (child) { return child.textContent; }).join("");
      |    }
      |    set textContent(value) {
      |      const text = value === null || value === undefined ? "" : String(value);
      |      if (this.nodeType === 3 || this.nodeType === 8) { this.data = text; return; }
      |      this.childNodes.forEach(function (child) { child.parentNode = null; });
      |      this.childNodes = [];
      |      if (text !== "") this.appendChild(new Text(text));
      |    }
      |    appendChild(child) { return this.insertBefore(child, null); }
      |    insertBefore(child, reference) {
      |      // The DOM's pre-insert rule: inserting a node before itself leaves it where it is.
      |      if (reference === child) return child;
      |      if (child.parentNode) child.parentNode.removeChild(child);
      |      if (reference === null || reference === undefined) {
      |        this.childNodes.push(child);
      |      } else {
      |        const index = this.childNodes.indexOf(reference);
      |        if (index === -1) {
      |          throw new Error("insertBefore: " + reference.nodeName + " is not a child of " + this.nodeName);
      |        }
      |        this.childNodes.splice(index, 0, child);
      |      }
      |      child.parentNode = this;
      |      return child;
      |    }
      |    removeChild(child) {
      |      const index = this.childNodes.indexOf(child);
      |      if (index === -1) throw new Error("removeChild: not a child");
      |      this.childNodes.splice(index, 1);
      |      child.parentNode = null;
      |      return child;
      |    }
      |    replaceChild(newChild, oldChild) {
      |      if (newChild === oldChild) return oldChild;
      |      const index = this.childNodes.indexOf(oldChild);
      |      if (index === -1) throw new Error("replaceChild: not a child");
      |      if (newChild.parentNode) newChild.parentNode.removeChild(newChild);
      |      this.childNodes[index] = newChild;
      |      newChild.parentNode = this;
      |      oldChild.parentNode = null;
      |      return oldChild;
      |    }
      |    contains(node) { while (node) { if (node === this) return true; node = node.parentNode; } return false; }
      |    get className() { return this.getAttribute("class") || ""; }
      |    set className(value) { this.setAttribute("class", value); }
      |    setAttribute(name, value) {
      |      if (value === null || value === undefined) this.removeAttribute(name);
      |      else this.attributes[name] = String(value);
      |    }
      |    setAttributeNS(namespace, name, value) { this.setAttribute(name, value); }
      |    getAttribute(name) {
      |      return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
      |    }
      |    getAttributeNS(namespace, name) { return this.getAttribute(name); }
      |    hasAttribute(name) { return this.getAttribute(name) !== null; }
      |    removeAttribute(name) { delete this.attributes[name]; }
      |    removeAttributeNS(namespace, name) { this.removeAttribute(name); }
      |    addEventListener(type, listener) { (this.listeners[type] = this.listeners[type] || []).push(listener); }
      |    removeEventListener(type, listener) {
      |      const index = (this.listeners[type] || []).indexOf(listener);
      |      if (index !== -1) this.listeners[type].splice(index, 1);
      |    }
      |    dispatchEvent(event) {
      |      (this.listeners[event.type] || []).slice().forEach(function (listener) { listener(event); });
      |      return true;
      |    }
      |    /** What the browser does when the reader types or clicks: an event aimed at this node. */
      |    fire(type) { this.dispatchEvent({ type: type, target: this }); }
      |    /** Laminar hands out nodes, not selectors; the tests need to find what it built. */
      |    find(predicate) {
      |      if (this.nodeType === 1 && predicate(this)) return this;
      |      for (const child of this.childNodes) {
      |        const found = child.find(predicate);
      |        if (found) return found;
      |      }
      |      return undefined;
      |    }
      |    findAll(predicate) {
      |      const found = this.nodeType === 1 && predicate(this) ? [this] : [];
      |      for (const child of this.childNodes) found.push.apply(found, child.findAll(predicate));
      |      return found;
      |    }
      |  }
      |
      |  class Element extends Node {}
      |  class HTMLElement extends Element {
      |    constructor(tagName) { super(1, tagName); }
      |  }
      |  class Text extends Node {
      |    constructor(data) { super(3, "#text"); this.data = String(data); }
      |  }
      |  class Comment extends Node {
      |    constructor(data) { super(8, "#comment"); this.data = String(data); }
      |  }
      |  class HTMLAnchorElement extends HTMLElement {}
      |  class HTMLButtonElement extends HTMLElement {}
      |  class HTMLInputElement extends HTMLElement {}
      |  class HTMLOptionElement extends HTMLElement {}
      |  class HTMLSelectElement extends HTMLElement {}
      |  class HTMLTextAreaElement extends HTMLElement {}
      |
      |  function constructorFor(tag) {
      |    switch (tag) {
      |      case "A": return HTMLAnchorElement;
      |      case "BUTTON": return HTMLButtonElement;
      |      case "INPUT": return HTMLInputElement;
      |      case "OPTION": return HTMLOptionElement;
      |      case "SELECT": return HTMLSelectElement;
      |      case "TEXTAREA": return HTMLTextAreaElement;
      |      default: return HTMLElement;
      |    }
      |  }
      |
      |  class Document extends Node {
      |    constructor() {
      |      super(9, "#document");
      |      this.documentElement = new HTMLElement("HTML");
      |      this.body = new HTMLElement("BODY");
      |      this.documentElement.appendChild(this.body);
      |      this.appendChild(this.documentElement);
      |    }
      |    createElement(name) { return new (constructorFor(String(name).toUpperCase()))(String(name).toUpperCase()); }
      |    createElementNS(namespace, name) { return this.createElement(name); }
      |    createTextNode(data) { return new Text(data); }
      |    createComment(data) { return new Comment(data); }
      |  }
      |
      |  const dom = {
      |    Node: Node, Element: Element, HTMLElement: HTMLElement, Text: Text, Comment: Comment,
      |    HTMLAnchorElement: HTMLAnchorElement, HTMLButtonElement: HTMLButtonElement,
      |    HTMLInputElement: HTMLInputElement, HTMLOptionElement: HTMLOptionElement,
      |    HTMLSelectElement: HTMLSelectElement, HTMLTextAreaElement: HTMLTextAreaElement
      |  };
      |  Object.keys(dom).forEach(function (name) { globalThis[name] = dom[name]; });
      |  return new Document();
      |})()""".stripMargin

  /** A fresh document, with the DOM classes installed as globals. */
  def document(): js.Dynamic = js.eval(source).asInstanceOf[js.Dynamic]
}

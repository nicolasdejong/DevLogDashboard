const $query    = function(selectors, node) { return (node ||document).querySelector(selectors); };
const $queryAll = function(selectors, node) { return Array.from((node ||document).querySelectorAll(selectors)); };
const secUpdateNodes = new Map();

let disableUpdates = false;

setInterval(() => secUpdateNodes.forEach( ({ node, model, onlyAttributes }) => updateVariablesIn(node, model, onlyAttributes)),
  1000); // updateVariablesIn is slow (~100ms)

function updateVariablesIn(node, model, onlyAttributes) {
  if(disableUpdates) return;
  if(typeof node === 'string') node = $queryAll(node);
  if(Array.isArray(node)) { node.forEach(n=>updateVariablesIn(n, model, onlyAttributes)); return; }

  if(!node) return;
  if(!model) model = {};
  function getValueOf(text) {
    const parts = text
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&amp;/g, '&')
      .replace(/\n/g, ' ')
      .split(/\s+\|\s+/);
    if(parts.slice(1).some(cmd => cmd === 'update')) periodicallyUpdateNode(node, model, onlyAttributes);
    const name = parts[0];
    if( /^\w+$/.test(name) ) return this[name];

    // This is too slow. It needs to be replaced later.
    try {
      node.lastEval = eval(`with(this) { ${name} }` );
      return node.lastEval;
    } catch(e) {
      console.error('Failed to eval: ' + name + ' -- ' + e.message);
      console.info('model:', model);
      return '';
    }
  }
  function replaceVarsOf(text) {
    return (text || '')
      .replace(/\${([^}]+)}/g, (match, name) => getValueOf.call(model, name) || '');
  }

  if (!node.originalInnerHTML) {
    node.originalInnerHTML = node.innerHTML;
    node.originalAttrs = {};
    Array.from(node.attributes).forEach(attr => node.originalAttrs[attr.name] = attr.value);
  }

  if(!onlyAttributes) {
    const generatedHtml = replaceVarsOf(node.originalInnerHTML);
    if(node.prevGeneratedHTML !== generatedHtml) node.innerHTML = node.prevGeneratedHTML = generatedHtml;
    //if(node.innerHTML !== newHtml) console.log('node replaced:', newHtml);
  }

  Array.from(node.attributes).forEach(attr => {
    attr.value = replaceVarsOf(node.originalAttrs[attr.name]);
    if (('' + node.originalAttrs[attr.name]).includes('starting')) node.originalAttrs[attr.name] = node.originalAttrs[attr.name].replace(/starting/,''); // hack :-(
  });
}
function periodicallyUpdateNode(node, model, onlyAttributes) {
  //secUpdateNodes.set(node, () => updateVariablesIn(node, model, onlyAttributes)); <-- Major (Chrome!) memory leak
  secUpdateNodes.set(node, { node, model, onlyAttributes });
}
function setTemplateIn(node, templateName) {
  const template = $query('#template-' + templateName);
  Array.from(template.attributes)
    .filter(attr => attr.name !== 'id')
    .forEach(attr => node.setAttribute(attr.name, attr.value));
  node.innerHTML = template.innerHTML;
}
function createTemplateNode(templateName, model) {
  const node = document.createElement('div');
  setTemplateIn(node, templateName);
  if(model) updateVariablesIn(node, model);
  return node;
}

function getUpAttribute(node, name) {
  if (!node || !node.getAttribute) return undefined;
  const attr = node.getAttribute(name);
  return attr || getUpAttribute(node.parentNode, name);
}
function toggleAttribute(node, attrName, force) {
  if (!node) return;
  if (force === undefined) force = !node.hasAttribute(attrName);
  if (node.hasAttribute(attrName) !== force) {
    if (force) node.setAttribute(attrName, '');
    else       node.removeAttribute(attrName);
  }
}

function getCssRules(selectorText) {
  const rules = Array.from(document.styleSheets).find(ss=>ss.href==null&&(ss.cssRules||[]).length>50) || {};
  return selectorText ? Array.from(rules.cssRules || []).find(rule => rule.selectorText === selectorText) : rules;
}

function debounce(timeMs, func) {
  if (typeof timeMs === 'function') { func = timeMs; timeMs = 100; }
  const debounceName = 'debounced.' + Math.random();
  return function() {
    const args = arguments;
    setNamedTimeout(debounceName, timeMs, () => {
      func.apply(null, args);
    });
  };
}
const debounced = debounce;

const _namedTimeouts = new Map();
function setNamedTimeout(name, timeMs, callback) {
  function triggered() {
    clearNamedTimeout(name);
    callback();
  }
  const existingTimeout = _namedTimeouts.get(name);
  if(existingTimeout) clearTimeout(existingTimeout);
  _namedTimeouts.set(name, setTimeout(triggered, timeMs));
}
function clearNamedTimeout(name) {
  const handle = _namedTimeouts.get(name);
  if(handle) clearTimeout(handle);
  _namedTimeouts.delete(name);
}

function timeAgo(ms) {
  if(ms < 1000) return '';
  const now = new Date().getTime();
  const dt = now - ms;
  const allSecs  = Math.floor(dt / 1000);     const secs  = allSecs % 60;
  const allMins  = Math.floor(allSecs / 60);  const mins  = allMins % 60;
  const allHours = Math.floor(allMins / 60);  const hours = allHours % 24;
  const allDays  = Math.floor(allHours / 24);

  if(allHours > 48) return allDays + 'd' + hours + 'h';
  if(allHours > 20) return allHours + 'h';
  if(allMins > 60) return allHours + 'h' + mins + 'm';
  if(allMins > 10) return mins + 'm';
  return mins + 'm' + secs + 's';
}

function sleep(ms) {
  return new Promise(resolve => setTimeout( resolve, ms));
}

function addMouseHandlers(target, handlers) { //handlers { onMouseDown, onMouseUp, onMouseMove }
  const node = target || window;
  const listeners = {};
  const addEventListener    = (etarget, type, f) => {    listeners[type] = f; etarget.addEventListener(type, f); };
  const removeEventListener = (etarget, type   ) => { if(listeners[type]) etarget.removeEventListener(type, listeners[type]); listeners[type] = null; };
  const screenPos0 = { x:0, y: 0 };

  function setRelPos(evt) {
    evt.relX = evt.screenX - screenPos0.x;
    evt.relY = evt.screenY - screenPos0.y;
  }

  function onMouseDown(evt) {
    screenPos0.x = evt.screenX - evt.offsetX;
    screenPos0.y = evt.screenY - evt.offsetY;
    addEventListener(window, 'mouseup', onMouseUp);
    if(handlers.onMouseMove) addEventListener(window, 'mousemove', onMouseMove);
    if(handlers.onMouseDown) handlers.onMouseDown(evt);
  }
  function onMouseUp(evt) {
      setRelPos(evt);
    if(listeners.mousedown) removeEventListener(window, 'mouseup');
    if(listeners.mousemove) removeEventListener(window, 'mousemove');
    if(handlers.onMouseUp) handlers.onMouseUp(evt);
  }
  function onMouseMove(evt) {
    if(handlers.onMouseMove) {
      setRelPos(evt);
      handlers.onMouseMove(evt);
    }
  }

  if(handlers.onMouseDown)                          addEventListener(node, 'mousedown', onMouseDown);
  if(handlers.onMouseUp   && !handlers.onMouseDown) addEventListener(window, 'mouseup',   onMouseUp);
  if(handlers.onMouseMove && !handlers.onMouseDown) addEventListener(window, 'mousemove', onMouseMove);

  return listeners;
}

// Use this function for the replace function in String.replace(..., f) to get info instead of vararg
function replaceFunc(f) {
  return (...args) => { // args are [match, group1, group2, ..., offset, whole string, (some browsers:) named groups obj]
    const info = { // replace function will be called with this object
      match: args[0],    // full matching string
      groups: [args[0]], // matched groups per index and, if available, by name
      offset: -1,        // offset of match in all
      all: '',           // full text in which match was found
    };
    let i=1;
    while(i<args.length && 'number' !== typeof args[i]) info.groups.push(args[i++]);
    info.offset = args[i];
    info.all = args[i+1];
    Object.entries(args[i+2] || {}).forEach(([k,v]) => info.groups[k] = v);
    return f.call(null, info);
  };
}

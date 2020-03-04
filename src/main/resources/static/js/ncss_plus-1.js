// Nested css (ncss) to css converter by Nicolas de Jong
//
// V1.0/20180607 - initial version
//
// Nested css is a lot more readable than css which has lots of duplications.
// Load this script in the HEAD section and conversion is automatic. No build required.
//
// This is a small and simple client side css preprocessor with limited functionality.
// For more client-side css-preprocessing power, take a look at postcss or less.
// They are a lot bigger, but have many more conversions.
//
// The code is kept small by using regex instead of proper tokenization.
// This works quite well, but there are some corner cases where conversion fails.
// That typically happens with unmatched quotes in remarks.
// See the test files for examples.
//
// Notes:
// - Nesting is scss-compatible except for properties nesting.
// - Loading of ncss is not working for "file:" urls due to browsers being paranoid.
//   Use a (local) file-server or inline instead. This is both for href/src as @import.
//
// Versions:
//
// The basic ncss version (1KB) just adds nesting. If all you want is css nesting, use this one.
// The 'plus' version (6KB) adds a some minimal scss features that may just work for simple scss.
//
// ncss adds features to css for LINK and STYLE nodes of type "text/ncss" and "text/scss":
// - 1.2KB minimized
// - supports nesting css like scss except property nesting.
// - support for single-line comments (//...)
// - @import is not supported for ncss
// - ncss.js script loading must be last in HEAD
//
// The 'plus' version has the following extras:
// - 6.2KB minimized
// - supports:
//   - $variables by renaming them to css variables
//   - calculation by changing it to a calc() expression (e.g. margin: $border-width + 2px;)
//   - nested properties (border { color:.., width:.., style:.. })
//   - @import of ncss
//   - @import on any depth: '@import "name";' (where name starts with '_' (not part of real name) or ends with '.[ns]css')
//   - basic scss @mixin with args but without logic or maps. Use @include to use mixin.
//   - the simplest version of @extends. (simple selectors only, no deep selectors)
//   - @media at any depth, as in scss.
// - script flag attributes:
//   - 'expose' exposes extra converter and function plugins, and ncss to css conversion function
//   - 'watch' watches for DOM changes for new ncss LINK or SCRIPT nodes after loading.
//   - 'files' to load one or more ncss resources (instead of multiple LINKs)
// - ncss.js script loading can be anywhere in HEAD
//
// How to use:
//
//  <link  type="text/ncss" href="your-styles.ncss" rel="stylesheet">
//  <style type="text/ncss" href="your-styles.ncss"></script>
//
// and load this script: <script src="ncss.js" [nc expose watch files=...]></script> somewhere in head.
//
// Optional script attributes (ncss-plus):
// - watch   Watch for dynamically added link or style tags of type text/ncss to the DOM
// - files   Comma separated list of ncss files to load (can also be given in body of script tag)
// - expose  Makes 'ncss' global (on window) with the following contents:
//             - convertToCss(ncssText: string): string
//             - converters[]: (ncss: string, quotes:string[], vars:{string:string}, mixins:{string:string}) => string
//             - functions{}: string : (ncss: string, args: string[], context:{quotes:string[]}) => string
//             - options{}: string : string|boolean (from script attributes)
//           The converters and functions are initially empty but can be extended.
//           Adding converters or functions should be done before styles are loaded (move to beginning of head)

(function(globals) {
const undash       = s => s.replace(/-(.)/g, (m,g) => g.toUpperCase());
const usToDash     = s => s.replace(/_/g, '-');
const isSourceNode = node => /STYLE|LINK/.test((node||{}).nodeName) && /^text\/[ns]css/.test(node.type);
const watchForNcss = () => watchHandle || (watchHandle = new MutationObserver(mutations => {
                             mutations.forEach(m => m.addedNodes.forEach(n => !isSourceNode(n) || flattenNcss(n)));
                           }).observe(document, { childList:true, subtree:true }));
const stopWatch    = () => { !watchHandle || watchHandle.disconnect(); watchHandle = null; };
const urlFromNode  = node => node.getAttribute('href') || node.getAttribute('src');
const syncLoad     = nodeOrUrl => {
  if(nodeOrUrl.nodeName && !(nodeOrUrl = urlFromNode(nodeOrUrl))) return '';
  let url = nodeOrUrl;
  if(url.startsWith('_')) url = url.substr(1);
  if(loadedUrls.has(url)) return '';
  loadedUrls.add(url); // prevent duplicate loads & circular imports

  // synchronized load because a <link ...> call would block as well
  // also used for @imports.
  const request = new XMLHttpRequest();
  request.open('GET', url, /*async=*/false);
  request.send();

  if (request.status === 200) return request.responseText;
  console.error('failed to load:', url);
  return '';
};
const flattenNcss  = (styleNodeOrText, name) => {
  let text;
  let node;
  let load = false;

  if(styleNodeOrText.nodeName) {
    node = styleNodeOrText;
    text = node.textContent || ((load = true) && syncLoad(node));
    name = urlFromNode(node);
  } else text = styleNodeOrText;

  const newNode = document.createElementNS((node || {}).namespaceURI, 'style');
  newNode.type = 'text/css';
  newNode.textContent = convertNcssTextToCss(text, null, name);

  if(node) {
    if(node.media) newNode.setAttribute('media', node.media);
    node.parentNode.replaceChild(newNode, node);
  } else document.head.appendChild(newNode);
  if(load && node) {
    const loaded = document.createEvent('Event');
    loaded.initEvent('load', false, false);
    node.dispatchEvent(loaded);
  }
};
const regEsc             = s => s.replace(/([*?+[\]\/()\\-])/g, '\\$1');


const convertNcssTextToCss = (cssWithNesting, rootSelector, baseUrl) => {
  const qprefix = '=Q=' + (Math.random() + '=Q=').substr(2); // generating unique prefix supports recursion
  const tokens  = [];
  const quotes  = [];
  const vars    = {};
  const mixins  = {};
  const extnds  = [];

  const stringPipe         = (input, ...funcs) => flatten(funcs).reduce((result, func) => func.call(options, result, quotes, vars, mixins) || '', input);
  const indexOf            = (s, textOrRegExp, offset=0) => {
    if(typeof textOrRegExp === 'string') return s.indexOf(textOrRegExp, offset);
    const result = (textOrRegExp.exec(s.substring(offset)) || {index:-1}).index;
    return result < 0 ? result : result + offset;
  };
  const flatten            = array => array.reduce((acc, val) => acc.concat(Array.isArray(val)?flatten(val):val), []);
  const warn               = (...msg) => console.warn(`${baseUrl ? baseUrl : 'ncss'}: ` + msg.join(' ')) || '';
  const removeNested       = (s, begin, end, prefix='', blockHandler=null) => {
    let block = 0;
    while(0 <= (block = indexOf(s, prefix || begin, block))) {
      let openIndex = s.indexOf(begin, block);
      let closeIndex = s.indexOf(end, block);
      for(;;) {
        let bo2 = s.indexOf(begin, openIndex + begin.length);
        if(bo2 > openIndex && bo2 < closeIndex) { openIndex = bo2; closeIndex = s.indexOf(end, closeIndex + end.length); } else break;
      }
      const oldBlock = s.substring(block, closeIndex+end.length);
      let newBlock = '';
      if(blockHandler) newBlock = blockHandler(oldBlock, block) || '';
      if(oldBlock !== newBlock) {
        s = s.substr(0, block) + newBlock + s.substring(closeIndex+end.length);
      } else block++;
    }
    return s;
  };
  const fixNewlines        = s => s.replace(/\r\n|\r/g, '\n');
  const storeUntouched     = s => removeNested(s, '{', '}', /@[^{;]+{/, block => {
                                    block = removeDupWhites(block);
                                    if(/^@(supports|document)/.test(block)) {
                                      const newBlock = flatString(block.replace(/{[\s\S]+}$/, m => '{ ' + convertNcssTextToCss(m).replace(/ *\n/, ' ').trim() + ' }'));
                                      return qprefix + quotes.push(newBlock) + ';';
                                    }
                                    if(/^@(mixin|extend)/.test(block)) return block;
                                    if(/^@(media|include)/.test(block)) return scssVarsToCss(block);
                                    return qprefix + quotes.push(scssVarsToCss(block).replace(/\n/g, ' ') + '\n') + ';';
                                  }).replace(/@[^{;]+;/g, block => {
                                    if(/@(mixin|extend|media|include|import|content)/.test(block)) return block;
                                    return qprefix + quotes.push(block) + ';';
                                  });
  const storeQuotes        = s => s.replace(/(['"])(.*?[^\\])?\1|\([^)]+:\/\/[^)]+\)/g, q => qprefix + quotes.push(q));
  const restoreQuotes      = s => s.replace( new RegExp(regEsc(qprefix) + '(\\d+)', 'g'), (m, n) => restoreQuotes(quotes[n-1]));
  const removeCDATA        = s => s.replace(/<!\[CDATA\[([\s\S]*)]]>/g, '$1');
  const removeRemarks      = s => s.replace( /\/\*[\s\S]*?\*\//g, '').replace( /\/\/[^\n]*/g, '');
  const handleImports      = (s, otherBaseUrl) => {
    if(typeof otherBaseUrl !== 'string') otherBaseUrl = undefined;
    return s.replace(new RegExp('@import\\s*' + regEsc(qprefix) + '(\\d+)(\s*;)?', 'g'), (match, n) => {
      const importUrl = quotes[n-1].replace(/^(.)(.*)\1$/, '$2');
      if(!/^_|\.[sn]css$/.test(importUrl)) return match;
      const url = (otherBaseUrl || baseUrl).replace( /\/[^\/]+$/, '/' + importUrl);
      return stringPipe(syncLoad(url), fixNewlines, storeQuotes, removeRemarks, s => handleImports(s, url));
    });
  };
  const fixMissingSc       = s => s.replace(/(\w+\s*:\s*[\w"']+)( *[}\n])/g, (m, g1, g2, offset) => {
    warn(`missing semicolon for "${g1}"`);
    return g1 + ';' + g2;
  });
  const removeDupWhites    = s => s.replace(/ {2,}/g, ' ').replace(/\n[ \n]+/g, '\n');
  const flatString         = s => s.replace(/ *\n+/g, ' ').trim();
  const replaceNestedProps = s => s.replace(/([\w_-]+)\s*:\s*{([^}]+)}/, (_, main, subs) => subs.replace(/([\w-_]+)\s*:/g, (_,name) => main + '-' + name + ':'));
  const scoopMixins        = s => removeNested(s, "{", "}", "@mixin", mixin => {
    const [, name, args, body] = /^@mixin\s+([\w_-]+)(?:\s*\(([^)]+)\))?\s*{([\s\S]+)}$/.exec(mixin) || [];
    if(mixins[usToDash(name)]) warn(`duplicate @mixin "${name}"`); else
    mixins[usToDash(name)] = { name, args:args?args.split(sepCommaNewline).map(a=>a.split(/\s*:\s*/)):[], body:body };
  });
  const replaceIncludes    = s => {
    const outputMixin = (name, args, contentOrOffset, rnames) => {
      if(rnames.has(usToDash(name))) return warn(`endless recursion in @mixin "${name}"`); rnames.add(usToDash(name));
      const mixin = mixins[usToDash(name)];
      if(!mixin) { return warn(`unknown @include mixin: "${name}"`); }
      const reDots = /\.{3}$/;
      args = flatten((args ? args.split(sepCommaNewline) : []).map((arg, index) => reDots.test(arg) ? (vars[arg.replace(reDots,'').replace(/^\$/,'')]||'').split(sepCommaNewline): arg));
      args = args.map((arg, index) => reDots.test((mixin.args[index]||[])[0]) ? args.slice(index).join(', '): arg );
      let text = replace(mixin.body, rnames);
      let last = '0';
      mixin.args.forEach(([name, def], index) => {
        name = name.replace(reDots, '');
        text = text.split(name).join(last = (args[index] || def || last))
      });
      return text.replace(/@content\s*;?\s*/, typeof contentOrOffset === 'string' ? contentOrOffset : '');
    };
    const replace = (text, rnames) => stringPipe(text,
      text => text.replace(/\s*@include\s+([\w_-]+)(?:\s*\(([^)]+)\))?\s*;\s*/g, (_, name, args, coff) => outputMixin(name, args, coff, rnames || new Set())),
      text => removeNested(text, '{', '}', /@include\s+[^{\s+]/, block => {
        const ex = /^@include\s+([\w_-]+)(?:\s*\(([^)]+)\))?\s*{([\s\S]*)}$/.exec(block);
        return outputMixin.call(null, ex[1], ex[2], ex[3], rnames || new Set());
      })
    );
    return replace(s);
  };
  const runFunctions       = s => s.replace(/([\w-_]+)\(([^);]*)\)/g, (match, name, args) => (ncss.functions[name]||(()=>match)).call(ncss, args.split(sepCommaNewline)) || 0);
  const tokenize           = s => { s.replace(/([\s\S]*?)([;{}]|$)/g, (_, g1, g2) => tokens.push.apply(tokens, [g1, g2].map(s=>s.trim()).filter(s=>!!s))); return s; };
  const scssVarsToCss      = s => s.replace(/\$([-\w$]+)(\s*:\s*)(.+?);/g, (_, name, g2, val) => {
     vars[name] = /^\$/.test(val) ? vars[val.substring(1)] : val;
     return `--${name}${g2}${val};`;
  }).replace(/\$([-\w$]+)(\s+!important)?(\s*;)/g, 'var(--$1)$2$3');
  const normalizeStyle     = s => {
    const parts = s.split(/\s*:\s*/);
    if(!parts[1]) return s;
    return parts[0] + ': ' + parts[1].replace( /\(?((\d+\w*)|\$[\w_-]+)(\s*[+=/*]\s*\(?((\d+\w*)|\$[\w_-]+)\)?)+/g, m => {
      const withVars = m.replace(/\$([\w_-]+)/g, 'var(--$1)');
      return `calc(${withVars})`;
    });
  };
  const flattenRules       = () => {
    const reduceStyles = (styles, braced) => braced ? ` { ${styles.join(' ')} }` : styles.join('\n') + '\n';
    const addRules = (rules, selectors, styles) => {
      const joinSelectors = (a, b) => {
        if(reMedia.test(b)) {
          if(!a.trim()) return b + ' { ';
          const t = a;
          if(reMedia.test(a)) return a.split(/{/)[0].trim() + ' ' + b.replace(/@media/, 'and').trim() + ' { ' + (a.split(/{/)[1]||'').trim();
          a = b + ' {';
          b = t;
        }
        return b.includes('&') ? b.replace(/^(.*)&/, (_, prefix) => prefix ? prefix + ' ' + a : a) : a + ' ' + b;
      };
      rules.push([selectors, styles]);
      for(let token=';'; token; token=tokens.shift()) {
         if (token.startsWith(qprefix)) { rules.push([[''], [token]]); continue; }
        if(/^@extend/.test(token)) {
          extnds.push([token.substr(7).trim(), selectors]);
          continue;
        }
        if (token === '}') return rules;
        if (tokens[0] === '{') {
          tokens.shift();
          let deeperSelectors = token === ';' ? [''] : flatten(token.replace(/\n/g,' ').split(sepComma).map(tsel => selectors.map(sel => joinSelectors(sel, tsel).trim())));
          let deeperStyles = [];
          addRules(rules, deeperSelectors, deeperStyles);
          if(deeperSelectors.some(ds=>reMedia.test(ds)) && deeperStyles.length) deeperStyles.push('}');
        } else if(token !== ';') {
          styles.push(normalizeStyle(token) + ';');
        }
      }
      return rules;
    };
    const ruleToString = (selectors, styles) => {
      const nested = selectors[0];
      if(nested) {
        return selectors.join(', ') + reduceStyles(styles, !!styles.join('').trim());
      }
      // vars at LL0 are not supported by css
      const isVar = s => /^(\$|--|var\().*/.test(s);
      const vars = styles.filter(isVar);
      const rest = styles.filter(s => !isVar(s));
      return (rest.length ?           reduceStyles(rest, false) : '')
           + (vars.length ? ':root' + reduceStyles(vars, true ) : '');
    };
    const updateRulesForExtends = (rules) => { // rules is [ [selectors, styles] ]
      const arrayStartsWith = (array, values) => values.every((val, index) => array[index] === val);
      let extnd;
      while(extnd = extnds.shift()) {
        const [targetSelector, srcSelectors] = extnd;
        let found = false;
        rules.forEach(rule => {
          const [selectors, styles] = rule;
          if(selectors.some(sel=>reAt.test(sel))) return; // not in @directives
          /*
          srcSelector = .test
          targetSelector = .foo

          .a, .foo, .b {}      --> .a, .foo, .b, .test {}
          .a, .foo.bar, .b {}  --> .a, .foo.bar, .b, .test.bar {}

          targetSelector = b

          a b c, a b e {}      --> a b c, a b e, .test
          */
          const toAdd = [];
          srcSelectors.forEach(srcSelector => {
            selectors.forEach(sel => {
              if(sel === targetSelector) toAdd.push(srcSelector); else
              if(sel.startsWith(targetSelector) && /^\./.test(sel.substr(targetSelector.length))) {
                toAdd.push(srcSelector + sel.substr(targetSelector.length));
              }
            });
            selectors.splice(0, 9999, ...Array.from(new Set(selectors.concat(...toAdd))));
          });
        });
      }
    };

    const rules = addRules([], rootSelector ? [rootSelector] : [''], []);
    updateRulesForExtends(rules);
    return rules
      .filter(([selectors, styles]) => selectors.length && styles.length)
      .map(([selectors, styles]) => ruleToString(selectors, styles))
      .join('\n');
  };

  // A tradeoff is made here: no real tokenization is used which leads to much smaller code.
  // However there are some corner cases where this doesn't work well:
  // For example multiple multiline-remarks on a single line, each containing a single quote:
  //
  // > before remains/*single quote'*/this will be ignored/*single quote'*/after remains
  //
  return stringPipe(cssWithNesting || '',
    fixNewlines,
    storeQuotes, storeUntouched,
    removeRemarks, removeCDATA, handleImports, fixMissingSc,
    replaceNestedProps, scoopMixins, scssVarsToCss, replaceIncludes,
    ncss.converters, runFunctions,
    tokenize, flattenRules,
    removeDupWhites,
    restoreQuotes,
  ).replace(/[ \n]*\n */g, '\n');
};

const scriptNode = document.currentScript || {};
const loadedUrls = new Set();
const options    = Array.from(scriptNode.attributes||[]).reduce((obj, attr) => { obj[undash(attr.nodeName)]=attr.textContent || true; return obj; }, {});
const sepComma   = /\s*,\s*/;
const sepCommaNewline = /\s*[,\n]+\s*/;
const reMedia    = /@media/;
const reAt       = /^@/;
const urls       = (scriptNode.innerText||'').split(sepCommaNewline)
                     .concat((options.files || '').split(sepCommaNewline))
                     .filter(n=>!!n && typeof n === 'string');
const ncss = {
  convertToCss: convertNcssTextToCss,
  converters: [],
  functions: {},
  options
};
if(options.expose) globals.ncss = ncss;
let watchHandle;

// react to styles/links of type 'text/ncss'
watchForNcss();
if(!options.watch) window.addEventListener('load', () => stopWatch());

// flatten ncss urls & existing
if(urls.length) urls.forEach(url => flattenNcss(syncLoad(url), null, url));
['text/ncss','text/scss'].forEach(type => {
  document.querySelectorAll(`style[type="${type}"], link[type="${type}"]`).forEach(flattenNcss);
});
})(this || window);

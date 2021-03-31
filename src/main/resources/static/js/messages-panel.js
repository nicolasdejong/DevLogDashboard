let messagesPanel;
const maxLogChildCount = 5000;
const ansi_up = new AnsiUp;
ansi_up.use_classes = true;

let currentLineInfo;
let currentLineDiv;
let lastCollapsableDiv;
let previousLineDiv;

const consolidateLogDebounced = debounce(300, consolidateLog);
const updateSidelineDebounced = debounce(100, updateSideline);

initFunctions.push(initLogs);

function initLogs() {
  messagesPanel = $query('.messages');
}

function clearLog() { messagesPanel.innerHTML = ''; clearSideline(); }
function clearLogOnServer() { return Server.clearLog(getSelectedService()); }

function updateLog(service) {
  return Server.sendOutputOfService(service).then(output => {
    output.log.forEach(lineInfo => addToLog(lineInfo.type, lineInfo.text));
    service.state.logVelocity = output.logVelocity;
    setTimeout(() => setLogScrollPosFromSaved(service), 400);
  });
}

function scrollToTop() { messagesPanel.scrollTop = 0; }
function scrollToBottom() { messagesPanel.scrollTop = LARGE_YPOS; }
function isScrolledToBottom() {
  const scrollBottom = (messagesPanel.scrollHeight - messagesPanel.offsetHeight) - messagesPanel.scrollTop;
  // noinspection JSSuspiciousNameCombination
  return Math.abs(scrollBottom) < 20;
}
function setLogScrollPosFromSaved(service) {
  const scrollTop = localStorage['scrollPos.' + service.name];
  messagesPanel.scrollTop = scrollTop === undefined ? LARGE_YPOS : scrollTop;
}
function saveLogScrollPos(service) {
  localStorage['scrollPos.' + service.name] = isScrolledToBottom() ? LARGE_YPOS : messagesPanel.scrollTop;
}
function logScroll(scrollTop) {
  // There is a bug in Chrome that when scrolling and appending
  // child at the same time will result in a scroll to bottom.
  // Here prevent that
  if(scrollTop === LARGE_YPOS) {
    messagesPanel.scrollTop = scrollTop;
  } else {
    setTimeout(() => { messagesPanel.scrollTop = scrollTop }, 10);
    setTimeout(() => { messagesPanel.scrollTop = scrollTop }, 20);
  }
}

// This function is written in such a way that there are as few
// (expensive) DOM updates needed as absolutely necessary.
// So try to concatenate text until a different type of line
// appears and only then add the text to the node.
//
function addToLog(classNames, text, replaces) {
  classNames = Array.isArray(classNames) ? classNames : classNames.split(/\s+/);

  function createLineDiv(lineInfo) {
    currentLineDiv = document.createElement('div');
    currentLineDiv.className = [
      'line',
      lineInfo.isWarnLine ? 'WARN ' : '',
      lineInfo.isDebugLine ? 'DEBUG' :'',
      lineInfo.isErrorLine && !classNames.includes('ERROR') ? 'ERROR' :'',
      classNames.join(' '),
      lineInfo.isCollapsable ? 'expandable' : ''
    ].filter(s => !!s).join(' ');

    if(lineInfo.isCollapsable) {
      currentLineDiv.innerText = lineInfo.text;
      currentLineDiv.setAttribute('click.eval', 'handleLogClick(event)');
      appendDivToLog(currentLineDiv);
      lastCollapsableDiv = currentLineDiv;
      currentLineDiv = undefined;
    }
  }

  if(replaces) {
    // replace previous line
    if(currentLineDiv && currentLineInfo) {
      const lastNewline = currentLineInfo.text.lastIndexOf('\n');
      if(lastNewline < 0) {
        currentLineInfo = getLineInfo(classNames, text);
      } else {
        currentLineInfo.text = currentLineInfo.text.substring(0, lastNewline);
      }
      consolidateLog(); // immediately add to DOM
    } else {
      if(previousLineDiv) { // previous line was already added to DOM -- update DOM
        const lastNewline = previousLineDiv.innerText.lastIndexOf('\n');
        previousLineDiv.innerText = (lastNewline < 0 ? '' : previousLineDiv.innerText.substring(0, lastNewline))
                                  + text;
      }
    }
  } else {
    // just add new line
    const lineInfo = getLineInfo(classNames, text);
    if(currentLineInfo && currentLineInfo.needsConsolidation(lineInfo)) consolidateLog();

    if(!currentLineDiv) createLineDiv(lineInfo);
    if(currentLineInfo) {
      currentLineInfo.text += '\n' + lineInfo.text;
      currentLineInfo.hasAnsi |= lineInfo.hasAnsi;
    }
    else currentLineInfo = lineInfo;

    consolidateLogDebounced();
  }

}

function consolidateLog() {
  if(currentLineDiv && currentLineInfo) {
    if(currentLineInfo.hasAnsi) {
      currentLineDiv.innerHTML = ansi_up.ansi_to_html(currentLineInfo.text);
    } else {
      currentLineDiv.innerText = currentLineInfo.text;
    }
    if (currentLineInfo.isCollapsableChild && lastCollapsableDiv) {
      lastCollapsableDiv.appendChild(currentLineDiv);
      lastCollapsableDiv = undefined;
    } else {
      appendDivToLog(currentLineDiv);
    }
    previousLineDiv = currentLineDiv;
  }
  limitLogs();
  updateSidelineDebounced();
  currentLineDiv = null;
  currentLineInfo = null;
}

function appendDivToLog(div) {
  const atBottom = isScrolledToBottom();
  div.originalHTML = div.innerHTML;
  updateHides(div);
  messagesPanel.append(div);
  logScroll(atBottom ? LARGE_YPOS : messagesPanel.scrollTop);
}

const hides = { // Damn. Safari doesn't support lookbehinds. Named groups isn't working well over browsers either.
                // So: first group, if any, should be included in the replacement!
  date:           /(^|(?:<br>))\s*[\d-]*20\d\d(?:-[\d-]+)?\s*/mg,
  'process id':   /\d{2,7} (?=--- )/mg,
  trace:          /\s\[traceid=[^\]]+]/mg,
  thread:         /(--- )\[[^\]]+]/mg,
  package:        /\s\.?\w\.(?:\w+\.)+[\w$]+\s+:/mg, // expect at least two dots (excluding dot on 0) prevent false positives
  'first braced': /((?:^|(?:<br>))[^[]+)\s\[[^\]]+\]/mg,
  'empty lines':  /(<br>)(\s*<br>)+/g,
};
let hidesToUse = localStorage.hidesToUse ? JSON.parse(localStorage.hidesToUse) : [];

function updateHides(msgDiv) {
  if(!msgDiv) {
    $queryAll('.messages .line').forEach(updateHides);
    return;
  }
  if(hidesToUse.length) {
    let html = msgDiv.originalHTML;
    hidesToUse.map(h => hides[h]).forEach(hide => html = html.replace(hide, replaceFunc(info => info.groups[1] || '')));
    msgDiv.innerHTML = html;
  } else {
    msgDiv.innerHTML = msgDiv.originalHTML;
  }
}

function getLineInfo(classNames, text) {
  const info = {};
  const textBegin = text.substring(0,32).toUpperCase();
  info.isErrorLine = (classNames || '').includes('ERROR') || textBegin.includes('ERROR');
  info.isWarnLine = textBegin.includes('WARN');
  info.isInfoLine = textBegin.includes('INFO');
  info.isDebugLine = textBegin.includes('DEBUG');
  info.isOtherLine = (classNames || '').includes('OTHER');
  const isExceptionLine = info.isErrorLine && /^((Caused|Wrapped) by: )?(\S+\.){1,}[^\s]*Exception(: .*)?$/.test(text);
  const isStackTraceLine = info.isErrorLine && (/^\t((\.\.\. \d+ more)|(at (\S+\.){1,}.*))$/.test(text));
  info.isCollapsable = isExceptionLine;
  info.isCollapsableChild = isStackTraceLine;

  info.text = text;
  info.classNames = classNames;
  info.needsConsolidation = otherInfo => {
    return info.isErrorLine !== otherInfo.isErrorLine
        || info.isWarnLine !== otherInfo.isWarnLine
        || info.isOtherLine !== otherInfo.isOtherLine
        || info.isCollapsable !== otherInfo.isCollapsable
        || info.isCollapsableChild !== otherInfo.isCollapsableChild;
  };
  info.hasAnsi = /\033\[/.test(text);
  return info;
}

function limitLogs() {
  // limit the length to prevent slowdown of browser
  while (messagesPanel.children.length > maxLogChildCount) {
    messagesPanel.removeChild(messagesPanel.firstChild);
  }
}

function handleLogClick(evt) {
  const node = evt.target;
  if (node && node.classList.contains('expandable')) {
    node.classList.toggle('expanded');
    updateSideline();
  }
}

function clearSideline() {
  const canvas  = $query('.messages-sidebar canvas');
  const ctx     = canvas.getContext('2d');
  const cheight = canvas.height;
  const cwidth  = canvas.width;
  ctx.clearRect(0, 0, cwidth, cheight);
}

function updateSideline() {
  const messages= $query('.data-panel .messages');
  const sidebar = $query('.messages-sidebar');
  const canvas  = $query('.messages-sidebar canvas');
  const ctx     = canvas.getContext('2d');
  const setHidden = isHidden => {
    const hide = isHidden || isHidden === undefined;
    messages.style.marginRight = hide ? '0' : null;
    sidebar.style.display = hide ? 'none' : 'block';
  };

  if(messages.scrollHeight <= messages.clientHeight) { setHidden(); return; }

  if(canvas.hideSidebar !== localStorage.hideSidebar) {
    canvas.hideSidebar = localStorage.hideSidebar === '1';
    setHidden(+canvas.hideSidebar);
    if(+canvas.hideSidebar) return;
  }
  setHidden(false);


  const lines   = $queryAll('.messages .line:not(.expanded)'); // note: this includes expanded lines, just not the expand-parent
  const lheight = lines.reduce((a, l) => a + l.clientHeight, 0);
  const cheight = canvas.height;
  const cwidth  = canvas.width;
  const mul     = cheight / (lheight + 1);
  const colorOf = node => window.getComputedStyle(node).getPropertyValue('color');
  let y = 0;
  let lineColor = '#fff';
  let otherColor = null;
  let warnColor = null;
  let errorColor = null;

  ctx.fillStyle = lineColor;
  ctx.fillRect(0, 0, cwidth, cheight);

  lines.forEach(line => {
    const h = line.clientHeight;
    if(!h) return;
    const isWarn  = line.classList.contains('WARN');
    const isError = line.classList.contains('ERROR');
    const isOther = line.classList.contains('OTHER');

    if(isWarn  && !warnColor ) warnColor  = colorOf(line);
    if(isError && !errorColor) errorColor = colorOf(line);
    if(isOther && !otherColor) otherColor = colorOf(line);
    if(!isWarn && !isError && !isOther && !lineColor) { lineColor  = colorOf(line); }

    const color = isWarn ? warnColor : (isError ? errorColor : (isOther ? otherColor : lineColor));
    const ch = h * mul;

    if(color !== lineColor) {
      ctx.fillStyle = color;
      ctx.fillRect(0, y, cwidth, Math.max(ch, 1)); // prevent too small lines (e.g. when log is very long)
    }
    y += ch;
  });
}

function addSideLineHandlers() {
  const canvas = $query('.messages-sidebar canvas');
  const messages = $query('.messages');
  function setPos(y) {
    const canvasPart = y / canvas.clientHeight;
    const maxScrollTop = messages.scrollHeight - messages.clientHeight;
    messages.scrollTop = maxScrollTop * canvasPart;
  }
  addMouseHandlers(canvas, {
    onMouseDown: evt => { evt.preventDefault(); setPos(evt.offsetY); },
    onMouseMove: evt => { evt.preventDefault(); setPos(evt.relY); }
  });
}

// This code started out small, so was done natively.
// By now a model-view library should be used because there
// are too many hacks. When I have time this code should be replaced.

console.log('Started at', moment().format('H:mm:ss'));

const LARGE_YPOS = 999999999;
const MOUSEDOWN_LONG_TIMEOUT = 500;

let services = [];
let servicesUnfiltered = [];
let selectedService;
let mainInfo = { version: 0, latestVersion: 0 };
let mousedownTimer = null;
let preventNextClick; // prevent 'click' event when 'longclick'


initFunctions.push(init);

function init() {
  window.addEventListener('resize', resized);
  window.addEventListener('click', clicked);
  window.addEventListener('mousedown', mousedown);
  window.addEventListener('mouseup', mouseup);
  window.addEventListener('beforeunload', aboutToUnload);
  window.addEventListener('storage', updateForSettings);
  window.addEventListener('hashchange', evt => {
    services = filterServices(servicesUnfiltered);
    updateForNewServices();
    if(selectedService) selectedService.selected = true;
  });
  window.addEventListener('keydown', evt => { if(evt.key === 'Control') document.body.classList.toggle('special', true); });
  window.addEventListener('keyup',   evt => { if(evt.key === 'Control') document.body.classList.toggle('special', false); });
  window.addEventListener('blur',    evt => {                           document.body.classList.toggle('special', false); });

  Server.subscribe('process-output',         response => handleProcessOutput(JSON.parse(response.body)));
  Server.subscribe('clear-process-output',   response => clearLog());
  Server.subscribe('service-state-changes',  response => handleServiceChanges(JSON.parse(response.body)));
  Server.subscribe('service-log-velocities', response => handleLogVelocities(JSON.parse(response.body)));
  Server.subscribe('last-state-history',     response => handleLastStateHistory(JSON.parse(response.body)));
  Server.subscribe('services-reloaded',      response => location.reload(/*noCache=*/true));
  Server.subscribe('scripts-changed',        response => location.reload(/*noCache=*/true));
  Server.subscribe('upgrade-available',      response => checkForNewVersion());
  Server.subscribe('port-changed',           response => {
    const port = JSON.parse(response.body).port;
    const newLoc = location.href.replace(/:\d+/, ':' + port).split(/[#?]/)[0];
    testConnection();
    function testConnection() {
      const scriptNode = document.createElement('script');
      scriptNode.onload = () => { location.href = newLoc; };
      scriptNode.onerror = () => { setTimeout(testConnection, 500); document.head.removeChild(scriptNode); };
      document.head.appendChild(scriptNode);
      scriptNode.src = newLoc;
    }
  });
  setTimeout(updateServices, 500);

  $query('.grid').style.minHeight = localStorage.gridHeight ? localStorage.gridHeight + 'px' : null;
  Server.getServerInfo().then(info => {
    if (!info || !info.introText) return;
    mainInfo = info;
    mainInfo.latestVersion = 0;
    document.title = info.introText.split(/by/)[0];
    updateVariablesIn($query('.info-page'), info);
    checkForNewVersion();
  });
  updateForSettings();
  setServerFlags();
  resized();
  initTabs();
  addSideLineHandlers();
}

function aboutToUnload() {
  if(selectedService) saveLogScrollPos(selectedService);
}

function resized() {
  const cells = $queryAll('.cell:not(template)');
  const cellSettings = $query('.cell-settings');

  if(!cells || !cells.length || cells.length < 2) {
    cellSettings.style.width = null;
    cellSettings.style.height = null;
    cellSettings.style.display = (!cells || !cells.length) ? 'none' : null;
    return;
  }
  const pageWidth = document.body.offsetWidth;
  const cellWidth = cells[cells[0].classList.contains('selected')?1:0].getBoundingClientRect().width;
  const cellsThatFitPerLine = Math.floor(pageWidth / (cellWidth-1));
  const cellsOnLastLine = (cells.length % cellsThatFitPerLine);
  const settingsRoom = (cellsThatFitPerLine - cellsOnLastLine) * cellWidth - 1;

  cellSettings.style.height = cellSettings.classList.contains('wide') ? null : cellWidth + 'px';
  cellSettings.style.width = settingsRoom + 'px';
  cellSettings.style.display = null;

  updateSideline(); // test=123
}

function mousedown(event) {
  mousedownTimer = setTimeout(() => { mousedownTimer = null; mousedownLong(event); }, MOUSEDOWN_LONG_TIMEOUT);
  handleAttrEval('mousedown', event);
}
function mouseup(event) {
  if(mousedownTimer) { clearTimeout(mousedownTimer); }
  else preventNextClick = true;
  handleAttrEval('mouseup', event);
}
function mousedownLong(event) {
  handleAttrEval('longclick', event);
}
function clicked(event) {
  if(preventNextClick) { event.preventDefault(); event.stopPropagation(); preventNextClick = false; return; }
  handleAttrEval('click', event);
}

function handleAttrEval(type, event) {
  const eventEval = getUpAttribute(event.target, type + '.eval');
  if(eventEval) {
    try { eval(eventEval);} catch (e) {
      console.log(`error evaluating: ${eventEval} -- ${e}`);
    }
  } else if(type === 'click') {
    let node = event.target;
    if(node && node.classList.contains('tab-button')) selectTab(node);
  }
}

function initTabs() {
  $queryAll('.tab-button:first-child').forEach(selectTab);
}
function selectTab(tabNode) {
  const tabButtons = $queryAll('.tab-button', tabNode.parentNode);
  const target = $query(tabNode.getAttribute('target') || '#none');
  const tabs = $queryAll('.tab-button', tabNode.parentNode).map(tb => $query(tb.getAttribute('target') || '#none')).filter(t=>!!t);
  tabButtons.forEach(tb => tb.classList.toggle('selected', tb === tabNode));
  tabs.forEach(tab => tab.classList.toggle('selected', tab === target));
}

function handleProcessOutput(output) {
  const service = getSelectedService();
  if(!service || service.uid !== output.suid) return; // ignore callback of previously selected service
  service.state.logVelocity = output.logVelocity;
  addToLog(output.line.type, output.line.text, output.line.replaces);
}

function updateServices() {
  Server.getServices()
    .then(newServices => {
      servicesUnfiltered = (newServices || []);
      services = filterServices(servicesUnfiltered);
      services.forEach(s => receivedService(s));
      updateForNewServices();
    })
    .then(() => Server.getStateHistory())
    .then(tts => {
      updateStateHistoryBlocks(tts);
    })
    .catch(error => {
      document.body.classList.toggle('app-starting', false);
      console.error('failed to retrieve services:', error);
    });
}
function updateForNewServices() {
  services.forEach(s => s.selected = false);
  updateVariablesIn($query('.info-page'), mainInfo);
  $query('.grid').style.minHeight = 'auto';
  $query('.grid').classList.toggle('one-service', services.length === 1);
  $query('.data-panel').classList.toggle('one-service', services.length === 1);
  createCellsForServices(services);
  localStorage.gridHeight = $query('.grid').clientHeight;// + (services.length ? 31 : 3);
  document.body.classList.toggle('app-starting', false);
  if(services.length === 1) selectService(services[0]);
  else selectService(localStorage.selectedService);
  resized();
}
function hashParts() {
  return decodeURI(location.hash||'#').substr(1).split(/[;&]/).filter(s=>s).map(s=>{
    const parts = /^(.*?)\s*(!?=+)\s*(.*)$/.exec(s) || [];
    return [parts[1], /^!=+$/.test(parts[2]), new RegExp(parts[3], /={2,}/.test(parts[1]) ? '' : 'i')];
  });
}
function filterServices(items) {
  const hash = hashParts();
  if(hash.length === 0) return items;
  return items.filter(item => hash.map(([key,not,val]) => !!('' + item[key]).match(val) ^ not).reduce((a,b)=>a&&b, true));
}
function createCellsForServices(services) {
  const cells = $query('.grid .cells');
  cells.innerHTML = '';
  if(services.length === 0) {
    cells.innerHTML = `<div class="no-services">${servicesUnfiltered.length ? 'No services in filter': 'No services configured'}</div>`;
  }
  services.forEach(service => {
    receivedService(service);
    service.cell = createTemplateNode('cell', service);
    cells.appendChild(service.cell);
  });
  updateForSettings();
  resized();
}
function getServiceWithName(name) {
  return services.find(s => s === name || s.name === name);
}
function getSelectedService() {
  if(selectedService) return selectedService;
  let selName = localStorage.selectedService;
  if(selName === 'null') selName = null;
  return services.find(s => s.name === localStorage.selectedService);
}
function selectService(name) {
  const service = getServiceWithName(name);
  if (service && service.selected) return;
  $query('.data-panel').classList.toggle("service", !!service);
  updateCellsForNewSelection(service);
  localStorage.selectedService = service ? service.name : '';

  if (service && service.name && selectedService !== service) {
    selectedService = service;
    consolidateLog(); // debounced -- it shouldn't be called after clearLog
    clearLog();
    updateLog(service);
  }
  if(!service) {
    selectedService = null;
    clearLog();
    Server.sendOutputOfService({name:'{non-existing}'}); // stops sending output
  }
  updateForSettings();
}

function updateCellsForNewSelection(service) {
  services.forEach(s => {
    if (s.selected) saveLogScrollPos(s);
    s.selected = (s === service);
    updateVariablesIn(s.cell, s);
  });
}
function handleLogVelocities(velocities) {
  services.forEach((service, index) => service.state.logVelocity = velocities[index]);
}

function openInNewWindow() {
  if(selectedService) { popout(); return; }
  window.open(location.href, '_blank', 'width=500,height=190').location.url = location.href;
}
function popout() {
  window.open(location.href, '_blank', 'width=1280,height=500').location = location.href.replace(/(#.*|)$/, '#name=' + encodeURI(selectedService.name));
}
function onlyThisService() {
  location.hash = '#name=' + encodeURI(regexEscape(selectedService.name));
}
function regexEscape(s) { return s.replace(/([.*?()])/g, '\\$1'); }

function startService(name) {
  const service = getServiceWithName(name);
  if (!service) return;
  Server.startService(service).then( () => {
    selectService(service);
  });
}
function stopService(name) {
  const service = getServiceWithName(name);
  if (!service) return;
  Server.stopService(service);
}

function handleServiceChanges(serviceUpdate) {
  const service = getServiceWithName(serviceUpdate.name);
  if (!service) { /*filtered out*/ return; }
  Object.assign(service, serviceUpdate);
  receivedService(service);
  updateVariablesIn(service.cell, service);
}

function receivedService(service) {
  if(!service.label) service.label = service.name.replace(/(serv)/i, '<br>$1');
  if($query('.grid.one-service')) service.label = service.label.replace(/<br>/g, ' ');
  service.state.timeStartedText = new Date(service.state.timeStarted).toISOString().replace(/[TZ]/g, ' ').replace(/\.\d{3}/,'');
}

function updateForSettings(varName) {
  if (varName) setServerFlags();

  if(localStorage.showRunTime       === undefined) localStorage.showRunTime = false;
  if(localStorage.showLogVelocity   === undefined) localStorage.showLogVelocity = false;
  if(localStorage.ignoreDeps        === undefined) localStorage.ignoreDeps = false;
  if(localStorage.startParallel     === undefined) localStorage.startParallel = false;
  if(localStorage.staticFont        === undefined) localStorage.staticFont = false;
  if(localStorage.cellsMatrixType   === undefined) localStorage.cellsMatrixType = 'auto';
  if(localStorage.showTime          === undefined) localStorage.showTime = false;
  if(localStorage.hideSidebar       === undefined) localStorage.hideSidebar = false;
  if(localStorage.ansiAltered       === undefined) localStorage.ansiAltered = '1';

  updateVariablesIn('.cell-settings .vars', localStorage);
  updateVariablesIn('.local-time', localStorage);
  const showRunTime       = !!+localStorage.showRunTime;
  const showLogVelocity   = !!+localStorage.showLogVelocity;
  const ignoreDeps        = !!+localStorage.ignoreDeps;
  const startParallel     = !!+localStorage.startParallel;
  const staticFont        = !!+localStorage.staticFont;
  const cellsMatrixType   =    localStorage.cellsMatrixType || 'auto';
  const cellsPerRowUseCfg = !!+localStorage.cellsPerRowUseCfg;
  const showTime          = !!+localStorage.showTime;
  const hideSidebar       = !!+localStorage.hideSidebar;
  const ansiAltered       = !!+localStorage.ansiAltered;

  toggleAttribute($query('#settings input[click\\.eval*=showRunTime]'),    'checked', showRunTime);
  toggleAttribute($query('#settings input[click\\.eval*=showLogVelocity]'),'checked', showLogVelocity);
  toggleAttribute($query('#settings input[click\\.eval*=ignoreDeps]'),     'checked', ignoreDeps);
  toggleAttribute($query('#settings input[click\\.eval*=startParallel]'),  'checked', startParallel);
  toggleAttribute($query('#settings input[click\\.eval*=showTime]'),       'checked', showTime);
  toggleAttribute($query('#settings input[click\\.eval*=staticFont]'),     'checked', staticFont);
  toggleAttribute($query('#settings input[click\\.eval*=cellsMatrixType]'),'checked', cellsMatrixType !== 'auto');
  toggleAttribute($query('#settings input[click\\.eval*=hideSidebar]'),    'checked', hideSidebar);
  toggleAttribute($query('#settings input[click\\.eval*=ansiAltered]'),    'checked', ansiAltered);
  updateVariablesIn(document.body, {}, /*excludeInner*/true);

  $query('.grid .cells').classList.toggle('show-runtime', showRunTime);
  $query('.grid .cells').classList.toggle('show-log-velocity', showLogVelocity);

  let fontSize = staticFont ? (localStorage.staticFontSize || '12') : '75%';
  if (/^\d+$/.test(fontSize)) fontSize += 'px';
  //getCssRules('.data-panel.static-font-size').style.fontSize = fontSize;
  $query('.data-panel').style.fontSize = fontSize;
  const fs = $query('select#fontSize');
  fs.style.pointerEvents = staticFont ? 'all' : 'none';
  fs.style.opacity       = staticFont ? 1 : 0.5;
  fs.value=String(staticFont ? fontSize : localStorage.staticFontSize).replace(/\D/g,'');

  const oneService = services.length === 1;
  const gridRules = Array.from(getCssRules().cssRules)
    .filter(r=>(r.selectorText||'').includes(oneService ? '.grid.one-service .cell' : '.grid:not(.one-service) .cell, .grid:not(.one-service) .cell-settings'))
    .filter(r=>(r.cssText||'').includes('width: calc'))
    [0];


  const defaultCellsPerRow = services.length <= 18 ? 9 : (services.length <= 24 ? 12 : 14);
  let cellsPerRow;
  switch(cellsMatrixType) {
    default:
    case 'auto':     cellsPerRow = defaultCellsPerRow; break;
    case 'fixed':    cellsPerRow = +localStorage.cellsPerRow || defaultCellsPerRow; break;
    case 'scount':   cellsPerRow = services.length; break;
    case 'scount+1': cellsPerRow = services.length + 1; break;
  }
  $query('#cellsPerRow').value=String(+localStorage.cellsPerRow);
  $query('#cellsPerRowContainer').style.display = cellsMatrixType === 'fixed' ? 'inline' : 'none';

  const cellsMatrixTypeCheck   = $query('input#cellsMatrixTypeCheckbox');
  const cellsMatrixTypeControl = $query('select#cellsMatrixType');
  cellsMatrixTypeControl.style.pointerEvents = cellsMatrixTypeCheck.checked ? 'all' : 'none';
  cellsMatrixTypeControl.style.opacity       = cellsMatrixTypeCheck.checked ? 1 : 0.5;
  cellsMatrixTypeControl.value = cellsMatrixType;

  if(!oneService) {
    gridRules.style.width = 'calc(' + (100/cellsPerRow) + '%)';
    gridRules.style.fontSize = 'calc(' + (100 - (cellsPerRow-9) * (cellsPerRow < 9 ? 20 : 5)) + '%)';
  }
  $query('.cell-settings').classList.toggle('wide', services.length % cellsPerRow === 0 && services.length);
  document.body.classList.toggle('ansi-altered', ansiAltered);
  resized();
}

function setServerFlags() {
  const setIgnoreDeps =    () => Server.setIgnoreDeps   (localStorage.ignoreDeps    === '1');
  const setStartParallel = () => Server.setStartParallel(localStorage.startParallel === '1');

  setIgnoreDeps().then(setStartParallel);
}

function checkForNewVersion() {
  Server.getLatestVersion().then(version => mainInfo.latestVersion = version)
    .then(() => {
      updateVariablesIn($query('.info-page'), mainInfo);
      updateVariablesIn($query('.right-top-buttons'), mainInfo);
    });
}

function showSideMenu(isTop) {
  updateVariablesIn($query('.side-menu'), window); // TODO: refactor to use getPopupMenuHandler(...)
  const menu = $query('.side-menu');
  if(menu.isShowing) return;
  if(!isTop) $queryAll('.side-menu > *').reverse().forEach(node => menu.appendChild(node));
  menu.style.top = isTop ? '2px' : null;
  menu.style.bottom = isTop ? null : '5px';
  setTimeout(() => { menu.style.display='block'; }, 10);
  const click = evt => { menu.style.display = 'none'; menu.isShowing = false; window.removeEventListener('click', click); };
  window.addEventListener('click', click);
  menu.isShowing = true;
}

function showUpgradeProgress() {
  $query('.upgrading-progress').style.display = 'block';
  const showStep = stepIndex => $queryAll('.steps .step')
    .forEach((n, i) => {
      n.classList.remove('current');
      if(i === stepIndex) n.classList.add('current');
    });
  const sum = ar => ar.reduce((t, a) => t + a, 0);

  // Since the server is upgrading, we don't have any knowledge of
  // what is going on there. But to make the waiting-time bearable
  // for the user make an educated guess on how long each step
  // takes, and leave the last step showing until a reload is
  // triggered by the newly started server.
  const stepTimes = [ 0, /*stopping time*/10, /*copy time*/2 /*starting time infinite*/ ];

  for(let i=0; i<stepTimes.length; i++) setTimeout(() => showStep(i), sum(stepTimes.slice(0,i+1)) * 1000);
}

function showLogLineFilters(evt) {
  const createHtml = () => Object.keys(hides).map(key =>
    `<div class="item"><label>`
    + `<input type="checkbox" name="hide-${key}" ${hidesToUse.includes(key)?'checked':''} onclick="updateFilters()">`
    + `Hide ${key}</label></div>\n`
  ).join('\n');
  const handler = getPopupMenuHandler(evt,
    '<span style="cursor:pointer;">Log Hides</span>',
    createHtml, {
      opened: handler => { handler.titlePanel.setAttribute('click.eval', 'toggleHides()'); },
      closed: handler => { handler.titlePanel.removeAttribute('click.eval'); }
    }
  );
  handler.open();
}
function toggleHides() {
  hidesToUse = hidesToUse.length > 0 ? [] : Object.keys(hides);
  localStorage.hidesToUse = JSON.stringify(hidesToUse);
  updateHides();
  const handler = getPopupMenuHandler();
  if(handler) handler.refresh();
  $query('#filter-button').style.color = hidesToUse.length ? 'orange' : null;
}
function updateFilters() {
  const handler = getPopupMenuHandler();
  hidesToUse.length = 0;
  $queryAll('input', handler.contentPanel).forEach(input => {
    if(input.checked) hidesToUse.push(input.getAttribute('name').replace(/hide-/,''));
  });
  localStorage.hidesToUse = JSON.stringify(hidesToUse);
  $query('#filter-button').style.color = hidesToUse.length ? 'orange' : null;
  updateHides();
}

function showStartServicePopup(evt, name) {
  const service = services.find(s => s.name === name);
  const jobs = service.jobs ? Object.keys(service.jobs) : [];
  const html =
    '<div style="cursor:pointer" click.eval="'
          + 'closePopupMenu();'
          + 'selectService(\'' + service.name + '\');'
          + 'Server.startService(\'' + service.name + '\', event.target.innerHTML.replace(/^<.*$/,\'\'));'
        +'">'
    + ['<b>default run</b>'].concat(jobs).reduce((a,i) => a += '<div class="item">' + i + '</div>\n', '')
    + '</div>';
  getPopupMenuHandler(evt, null, html).open();
}
function showLatestReleaseNotes(evt) {
  Server.getLatestReleaseNotes().then(rn => getPopupMenuHandler(evt,
    'Release Notes <button style="position:absolute;right:3em;user-select:none;" onclick="Server.upgrade(this);" title="Upgrade & restart DevLogDashboard. Note that services started from DevLogDashboard will be stopped.">Upgrade</button>',
    '<div style="white-space:pre;padding:0 1em 0 0;min-width:30em;">' + rn + '</div>'
  ).open());
}

function getPopupMenuHandler(evt, title='', html = '', callbacks = {}) {
  const popup = $query('.popup-menu');
  const h = popup['handler'] || {};
  if(evt) {
    popup.handler = h;
    h.popup = popup;
    h.headerPanel  = $query('.header', popup);
    h.titlePanel   = $query('.header .title', popup);
    h.closeButton  = $query('.header .close', popup);
    h.contentPanel = $query('.content', popup);
    h.buildContent = 'function' != typeof html  ? () => '' + html : () => html.call();
    h.buildTitle   = 'function' != typeof title ? () => '' + title : () => title.call();
    h.isInPanel = node => { for(;node;node=node.parentNode) if(node === popup) return true; return false; };
    h.mousedown = (evt) => { if(!h.isInPanel(evt.target) || evt.target === h.closeButton) h.close(); }; // not 'click' because of longclick.
    h.close = () => {
      if(!h.isOpen) return;
      h.isOpen = false;
      popup.style.display = 'none';
      window.removeEventListener('mousedown', h.mousedown);
      if(callbacks && callbacks.closed) callbacks.closed.call(null, h);
    };
    h.open = () =>  {
      h.close();

      window.addEventListener('mousedown', h.mousedown);

      const px = (evt ? evt.clientX - 50 : 0);
      const py = (evt ? evt.clientY - 10 : 0);
      popup.style.display = 'block';
      popup.style.left = (px - 50) + 'px';//'2em';
      popup.style.top  = (py - 10) + 'px';
      h.isOpen = true;

      h.headerPanel.style.display = title ? '' : 'none';
      h.titlePanel.innerHTML = h.buildTitle();
      h.contentPanel.innerHTML = h.buildContent();

      popup.style.left = Math.max(0, Math.min(document.body.clientWidth  - 10 - popup.clientWidth, px)) + 'px';
      popup.style.top  = Math.max(0, Math.min(document.body.clientHeight - 10 - popup.clientHeight, py)) + 'px';

      if(callbacks && callbacks.opened) callbacks.opened.call(null, h);
    };
    h.refresh = () => {
      if(h.isOpen) h.open();
    }
  }
  return popup.handler;
}
function closePopupMenu() {
  const handler = $query('.popup-menu').handler;
  if(handler) handler.close();
}

let socket;
let stompClient;
const runAfterConnect = [];
let isConnected = false;

initFunctions.push(initServer);

function initServer() {
  socket = new SockJS('/dashboard-websocket');
  stompClient = Stomp.over(socket);
  stompClient.debug = function() {}; // disable debug logging
  stompClient.connect({}, frame => {
    isConnected = true;
    runAfterConnect.forEach(r => r());
  }, message => {
    if(/Lost connection to/i.test(message)) Server.disconnected();
    else console.log('stomp message:', message);
  });
}

const whenConnected = (func) => { if (!isConnected) runAfterConnect.push(func); else func(); };

// Server.subscribe('test', response => {
//   console.log('received test message:', response);
// });
// Server.sendMessage('test', {name:'testData', foo:123});

class Server {

  static disconnected() {
    console.log('disconnected from server -- waiting for reconnect, then reload');
    $query('.body-content').classList.add('no-server');
    setInterval(() => Server.getServices().then(() => location.reload(/*noCache=*/true)).catch(() => ({})), 2000);

    if(mainInfo.upgrading) showUpgradeProgress();
  }

  static subscribe(topic, callback) {
    whenConnected(() => stompClient.subscribe('/topic/' + topic, response => callback(response)));
  }
  static unsubscribe(topic) {
    whenConnected(() => stompClient.unsubscribe(topic));
  }
  static sendMessage(topic, data) {
    whenConnected(() => stompClient.send('/app/' + topic, {}, JSON.stringify(data)));
  }

  static getServerInfo() {
    return fetch('serverInfo').then(resp => resp.json());
  }

  static getServices() {
    return fetch('services').then(resp => resp.json());
  }
  static sendOutputOfService(service) {
    return fetch('sendOutputOfService', {method:'post', body:service.name})
      .then(response => response.json());
  }
  static getStateOfService(service) {
    return fetch('getStateOfService', {method:'post', body:service.name})
      .then(response => response.json());
  }
  static getStateHistory() {
    return fetch('stateHistory', {method:'post'})
      .then(response => response.json());
  }
  static stopOutputOfService(service) {
  }
  static startService(service, job) {
    if('string' === typeof service) service = getServiceWithName(service);
    clearLog();
    return fetch('start', {
      method: 'post',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({serviceName:service.name, job:job||null}) }
    );
  }
  static stopService(service) {
    return fetch('stop', {method:'post', body:service.name})
  }
  static startAll() {
    return fetch('startAll', {method:'post'});
  }
  static stopAll(service) {
    return fetch('stopAll', {method:'post'});
  }

  static setIgnoreDeps(set) {
    return fetch('setFlags?startIgnoreDeps=' + !!set, {method:'post'});
  }
  static setStartParallel(set) {
    return fetch('setFlags?startParallel=' + !!set, {method:'post'});
  }
  static clearLog(service) {
    if(!service) return Promise.resolve();
    clearLog();
    return fetch('clearLog', {method:'post', body:service.name})
  }
  static getLatestVersion() {
    return fetch('getLatestVersion')
      .then(response => response.text())
      .then(text => +text)
      .catch(error => 0);
  }
  static getLatestReleaseNotes() {
    return fetch('getLatestReleaseNotes')
      .then(response => response.text())
      .catch(error => '[no release notes available]');
  }
  static upgrade(button) {
    if(!button) button = document.createElement('button'); // dummy
    button.style.pointerEvents = 'none';
    button.innerText = 'Upgrading...';
    mainInfo.upgrading = true;
    return fetch('upgrade', {method:'post'})
      .then(response => response.text())
      .then(text => {
        button.innerText = text;
      });
  }
}

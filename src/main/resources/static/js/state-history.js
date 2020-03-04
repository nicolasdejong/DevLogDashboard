const timeToStates = {};

function handleLastStateHistory(lastTimeToState) {
  updateStateHistoryBlocks(lastTimeToState);
}
function updateStateHistoryBlocks(tts) {
  if(tts) Object.assign(timeToStates, tts);
  const div = $query('#history');
  let lastRow = $query('.history-row:first-of-type');
  if(!div) return;
  if(lastRow) {
    const lastKey = Object.keys(timeToStates).sort().pop();
    if(lastKey !== lastRow.time) {
      const oldLastRow = lastRow;
      lastRow = document.createElement('div');
      lastRow.classList.add('history-row');
      div.insertBefore(lastRow, oldLastRow);
    }
    lastRow.innerHTML = '';
    createStateHistoryRow(lastRow, lastKey, timeToStates[lastKey])
  } else {
    const titleNode = div.removeChild(div.firstElementChild);
    Object.keys(timeToStates).sort().forEach(time => {
      const rowDiv = document.createElement('div');
      rowDiv.classList.add('history-row');
      createStateHistoryRow(rowDiv, time, timeToStates[time]);
      div.prepend(rowDiv);
    });
    div.prepend(titleNode);
  }
}
function createStateHistoryRow(targetDiv, time, timeStatesText) {
  const timeStates = timeStatesText.split(/,/);
  const dt = new Date(+time);
  const d2 = n => ((n < 10 ? '0' : '') + n);
  const timeDiv = document.createElement('div');
  timeDiv.innerHTML = dt.getHours() + ':' + d2(dt.getMinutes());
  targetDiv.append(timeDiv);
  targetDiv.time = time;

  //timeStates.forEach((states, serviceIndex) => createStateHistoryBlock(targetDiv, states, serviceIndex));
}
function createStateHistoryBlock(targetDiv, states, serviceIndex) {
    const lastStates = Array.from(new Set(states.split('').reverse())).slice(0,4).reverse();
    const [a, b, c, d] = lastStates;
    // borders are set tlbr
    let borderStates;
    switch(lastStates.length) {
      case 0: borderStates = ['o', 'o', 'o', 'o']; break;
      case 1: borderStates = [a, a, a, a]; break;
      case 2: borderStates = [a, a, b, b]; break;
      case 3: borderStates = [a, b, c, c]; break;
      case 4: borderStates = [a, b, c, d]; break;
    }
    swap(borderStates, 1, 3); // trbl
    const box = document.createElement('div');
    box.style.borderColor = borderStates.map(bs => 'var(--color-' + bs.toLowerCase() + ')').join(' ');
    const stateLabels = {
      O: 'off',
      W: 'waiting',
      S: 'starting',
      R: 'running',
      E: 'error',
      I: 'init_error'
    };
    box.setAttribute('title', (services[serviceIndex] || {name:'unknown'}).name + ' (' +
      states.split('').map(state => state.replace(/./, m => stateLabels[m] || m)).join(', ') + ')');
    targetDiv.appendChild(box);
}


function swap(a,i1,i2) { a || (a=[]); let t = a[i1]; a[i1]=a[i2]; a[i2]=t; }

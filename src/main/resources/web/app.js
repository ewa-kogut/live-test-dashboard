'use strict';

const STATUSES = {
  passed: { label: 'Passed', icon: '✓', rank: 3, colour: 'var(--passed)' },
  failed: { label: 'Failed', icon: '✕', rank: 0, colour: 'var(--failed)' },
  skipped: { label: 'Skipped', icon: '–', rank: 4, colour: 'var(--skipped)' },
  running: { label: 'Running', icon: '▸', rank: 1, colour: 'var(--running)' },
  queued: { label: 'Queued', icon: '', rank: 2, colour: 'var(--remaining)' },
};
const FILTERS = ['all', 'failed', 'passed', 'skipped', 'running', 'queued'];
const SVG_NS = 'http://www.w3.org/2000/svg';
const BARS = {
  height: 340, top: 26, right: 8, bottom: 66, left: 44, maxWidth: 24, gap: 2, minLabelSlot: 12,
};
const RING = { size: 200, radius: 80, thickness: 24, gap: 3 };
const PALETTE_KEY = 'live-test-dashboard-palette';

const element = (id) => document.getElementById(id);
const tooltip = element('tooltip');
const page = new URLSearchParams(window.location.search);
const pinnedRun = page.get('run');
const isHistoryView = page.get('view') === 'history';

let connected = false;
let everConnected = false;
let filter = 'all';
let run = newRun();
let activeBar = null;
let renderQueued = false;

function newRun() {
  return { state: 'waiting', startedAt: null, finishedAt: null, tests: new Map() };
}

// ---------- Events from the server ----------

function connect() {
  const query = pinnedRun === null ? '' : `?run=${encodeURIComponent(pinnedRun)}`;
  const stream = new EventSource(`/api/stream${query}`);
  stream.onopen = () => {
    // The server replays the whole run after every (re)connection.
    connected = true;
    everConnected = true;
    run = newRun();
    queueRender();
  };
  stream.onerror = () => {
    connected = false;
    queueRender();
  };
  stream.onmessage = (message) => {
    applyEvent(JSON.parse(message.data));
    queueRender();
  };
}

function applyEvent(event) {
  switch (event.type) {
    case 'reset':
      run = newRun();
      activeBar = null;
      hideTooltip();
      break;
    case 'run-started':
      run.state = 'running';
      run.finishedAt = null;
      run.startedAt = run.startedAt ?? event.at;
      event.tests.forEach(addTest);
      break;
    case 'run-finished':
      run.state = 'finished';
      run.finishedAt = event.at;
      break;
    case 'test-added':
      addTest(event.test);
      break;
    case 'test-started':
      updateTest(event.id, { status: 'running', message: null, location: null });
      break;
    case 'test-finished':
      finishTest(event);
      break;
    default:
      break;
  }
}

function addTest(test) {
  if (!run.tests.has(test.id)) {
    run.tests.set(test.id, { ...test, status: 'queued', order: run.tests.size });
  }
}

function updateTest(id, changes) {
  const test = run.tests.get(id);
  if (test) {
    Object.assign(test, changes);
  }
}

function finishTest(event) {
  const test = run.tests.get(event.id);
  if (!test) {
    return;
  }
  // Only failed tests run a second time, so an earlier finish means this one is a retry.
  const isRetry = test.finishedAt !== undefined;
  Object.assign(test, {
    status: event.status,
    finishedAt: event.at,
    durationMs: event.durationMs,
    message: event.message,
    // Runs saved before this field existed have no location.
    location: event.location ?? null,
    passedOnRetry: isRetry && event.status === 'passed',
  });
}

// ---------- Derived values ----------

function counts() {
  const result = { passed: 0, failed: 0, skipped: 0, running: 0, queued: 0 };
  run.tests.forEach((test) => {
    result[test.status] += 1;
  });
  return result;
}

function finishedTests() {
  return [...run.tests.values()]
    .filter((test) => ['passed', 'failed', 'skipped'].includes(test.status))
    .sort((a, b) => a.finishedAt - b.finishedAt || a.order - b.order);
}

function elapsedSeconds() {
  if (run.startedAt === null) {
    return 0;
  }
  const end = run.state === 'finished' ? run.finishedAt : Date.now();
  return Math.max(0, (end - run.startedAt) / 1000);
}

function formatClock(seconds) {
  const whole = Math.round(seconds);
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`;
}

function formatDuration(ms) {
  return ms === undefined ? '' : `${(ms / 1000).toFixed(1)} s`;
}

function niceStep(max, targetTicks, steps) {
  const ideal = max / targetTicks;
  return steps.find((step) => step >= ideal) ?? steps[steps.length - 1];
}

// ---------- Small DOM helpers: text always goes through textContent ----------

function html(tag, className, text) {
  const node = document.createElement(tag);
  if (className) {
    node.className = className;
  }
  if (text !== undefined && text !== null) {
    node.textContent = text;
  }
  return node;
}

function svg(tag, attributes = {}, text) {
  const node = document.createElementNS(SVG_NS, tag);
  Object.entries(attributes).forEach(([name, value]) => node.setAttribute(name, value));
  if (text !== undefined) {
    node.textContent = text;
  }
  return node;
}

function statusIcon(status) {
  const icon = html('span', `icon icon-${status}`, STATUSES[status].icon);
  icon.setAttribute('aria-hidden', 'true');
  return icon;
}

function legendItem(colour, label) {
  const item = html('span', 'legend-item');
  const swatch = html('span', 'swatch');
  swatch.style.background = colour;
  item.append(swatch, label);
  return item;
}

function showTooltip(content, x, y) {
  tooltip.replaceChildren(...content);
  tooltip.hidden = false;
  const box = tooltip.getBoundingClientRect();
  const left = Math.min(x + 14, window.innerWidth - box.width - 8);
  const top = Math.min(Math.max(8, y - box.height - 10), window.innerHeight - box.height - 8);
  tooltip.style.left = `${Math.max(8, left)}px`;
  tooltip.style.top = `${top}px`;
}

function hideTooltip() {
  tooltip.hidden = true;
}

// ---------- Rendering ----------

function queueRender() {
  if (!renderQueued) {
    renderQueued = true;
    requestAnimationFrame(() => {
      renderQueued = false;
      render();
    });
  }
}

function render() {
  const total = run.tests.size;
  const tally = counts();
  element('empty').hidden = total > 0;
  element('results').hidden = total === 0;
  renderState(tally);
  renderRunTitle();
  if (total === 0) {
    return;
  }
  element('finished-count').textContent = tally.passed + tally.failed + tally.skipped;
  element('total-count').textContent = total;
  renderElapsed();
  renderTiles(tally);
  renderRing(tally, total);
  renderBars();
  renderFilters(tally, total);
  renderTests();
}

function formatDate(millis) {
  return new Date(millis).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'medium' });
}

function renderRunTitle() {
  const title = element('run-title');
  title.hidden = run.startedAt === null;
  if (run.startedAt !== null) {
    const name = pinnedRun === null ? 'Latest run' : 'Run';
    title.textContent = `${name}, started ${formatDate(run.startedAt)}`;
  }
}

function renderElapsed() {
  element('elapsed').textContent =
      `${formatClock(elapsedSeconds())} ${run.state === 'finished' ? 'total' : 'elapsed'}`;
}

function renderState(tally) {
  const state = element('run-state');
  let text;
  let icon = null;
  if (!connected) {
    text = everConnected ? 'Connection lost, retrying…' : 'Connecting…';
  } else if (run.state === 'running') {
    text = 'Running';
    icon = 'running';
  } else if (run.state === 'finished') {
    text = tally.failed > 0 ? `Finished with ${tally.failed} failed` : 'Finished, no failures';
    icon = tally.failed > 0 ? 'failed' : 'passed';
  } else {
    text = 'Waiting for a test run';
  }
  state.replaceChildren(...(icon ? [statusIcon(icon)] : []), text);
  const total = run.tests.size;
  const finished = tally.passed + tally.failed + tally.skipped;
  const failures = tally.failed > 0 ? `${tally.failed} failed · ` : '';
  document.title = total > 0
    ? `${failures}${finished}/${total} · Live Test Dashboard`
    : 'Live Test Dashboard';
}

function renderTiles(tally) {
  const tiles = ['passed', 'failed', 'skipped', 'running', 'queued'].map((status) => {
    const tile = html('div', 'card tile');
    const label = html('div', 'tile-label');
    label.append(statusIcon(status), STATUSES[status].label);
    tile.append(label, html('div', 'tile-value', tally[status]));
    return tile;
  });
  element('tiles').replaceChildren(...tiles);
}

function renderRing(tally, total) {
  const parts = [
    { label: 'Passed', value: tally.passed, colour: STATUSES.passed.colour },
    { label: 'Failed', value: tally.failed, colour: STATUSES.failed.colour },
    { label: 'Skipped', value: tally.skipped, colour: STATUSES.skipped.colour },
    { label: 'Not finished', value: tally.running + tally.queued, colour: 'var(--remaining)' },
  ];
  const visible = parts.filter((part) => part.value > 0);
  const centre = RING.size / 2;
  const circumference = 2 * Math.PI * RING.radius;
  const gap = visible.length > 1 ? RING.gap : 0;
  const ring = svg('svg', { viewBox: `0 0 ${RING.size} ${RING.size}`, role: 'group' });
  ring.setAttribute('aria-label', 'Share of tests by result');

  let offset = 0;
  visible.forEach((part) => {
    const share = part.value / total;
    const description = `${part.label}: ${part.value} of ${total} (${Math.round(share * 100)}%)`;
    const length = Math.max(share * circumference - gap, 1);
    const arc = svg('circle', {
      class: 'ring-part',
      cx: centre,
      cy: centre,
      r: RING.radius,
      stroke: part.colour,
      'stroke-width': RING.thickness,
      'stroke-dasharray': `${length} ${circumference - length}`,
      'stroke-dashoffset': -offset,
      transform: `rotate(-90 ${centre} ${centre})`,
      tabindex: 0,
      'aria-label': description,
    });
    const show = (x, y) => showTooltip([html('div', '', description)], x, y);
    arc.addEventListener('mousemove', (event) => show(event.clientX, event.clientY));
    arc.addEventListener('mouseleave', hideTooltip);
    arc.addEventListener('focus', () => {
      const box = element('ring').getBoundingClientRect();
      show(box.left + box.width / 2, box.top + 20);
    });
    arc.addEventListener('blur', hideTooltip);
    ring.append(arc);
    offset += share * circumference;
  });

  const passRate = Math.round((tally.passed / total) * 100);
  ring.append(
      svg('text', {
        class: 'ring-value', x: centre, y: centre + 8, 'text-anchor': 'middle',
      }, `${passRate}%`),
      svg('text', {
        class: 'ring-caption', x: centre, y: centre + 28, 'text-anchor': 'middle',
      }, 'passed'),
  );
  element('ring').replaceChildren(ring);

  // Every part stays listed at zero, so the shape of this list does not change during a run.
  const rows = parts.map((part) => {
    const row = html('div', 'ring-row');
    const swatch = html('span', 'swatch');
    swatch.style.background = part.colour;
    row.append(
        swatch,
        html('span', 'ring-row-label', part.label),
        html('span', 'ring-row-count', part.value),
        html('span', 'ring-row-share', `${Math.round((part.value / total) * 100)}%`),
    );
    return row;
  });
  element('ring-legend').replaceChildren(...rows);
}

function barGeometry() {
  const container = element('chart');
  const tests = finishedTests();
  const width = Math.max(container.clientWidth, 280);
  const plotWidth = width - BARS.left - BARS.right;
  const plotHeight = BARS.height - BARS.top - BARS.bottom;
  const slot = plotWidth / Math.max(tests.length, 12);
  const barWidth = Math.max(Math.min(slot - BARS.gap, BARS.maxWidth), 2);
  const longest = Math.max(1, ...tests.map((test) => (test.durationMs ?? 0) / 1000));
  const step = niceStep(longest, 4, [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600]);
  const maxValue = Math.ceil(longest / step) * step;
  return { tests, width, plotWidth, plotHeight, slot, barWidth, step, maxValue };
}

function barPath(x, width, height, baseline) {
  const radius = Math.min(4, width / 2, height);
  const top = baseline - height;
  return `M${x} ${baseline}V${top + radius}Q${x} ${top} ${x + radius} ${top}`
      + `H${x + width - radius}Q${x + width} ${top} ${x + width} ${top + radius}V${baseline}Z`;
}

function renderBars() {
  const container = element('chart');
  const geometry = barGeometry();
  const { tests, width, plotWidth, plotHeight, slot, barWidth, step, maxValue } = geometry;
  const baseline = BARS.top + plotHeight;
  const y = (seconds) => baseline - (seconds / maxValue) * plotHeight;
  const chart = svg('svg', {
    viewBox: `0 0 ${width} ${BARS.height}`,
    height: BARS.height,
    'aria-hidden': 'true',
  });

  for (let value = 0; value <= maxValue; value += step) {
    chart.append(svg('line', {
      class: value === 0 ? 'axis-line' : 'grid-line',
      x1: BARS.left,
      x2: BARS.left + plotWidth,
      y1: y(value),
      y2: y(value),
    }));
    chart.append(svg('text', {
      x: BARS.left - 8, y: y(value) + 4, 'text-anchor': 'end',
    }, `${value} s`));
  }

  let slowest = null;
  const group = svg('g', { class: activeBar === null ? '' : 'bars-active' });
  tests.forEach((test, index) => {
    const seconds = (test.durationMs ?? 0) / 1000;
    // A skipped test takes no time, so it gets a small fixed bar to stay visible and hoverable.
    const height = Math.max((seconds / maxValue) * plotHeight, test.status === 'skipped' ? 10 : 3);
    const x = BARS.left + slot * index + (slot - barWidth) / 2;
    const bar = svg('g', { class: `bar${activeBar === index ? ' bar-active' : ''}` });
    bar.append(svg('path', {
      d: barPath(x, barWidth, height, baseline), fill: STATUSES[test.status].colour,
    }));
    // A cross above a failed bar means failures differ by more than colour alone.
    if (test.status === 'failed') {
      const cx = x + barWidth / 2;
      const cy = baseline - height - 9;
      bar.append(svg('path', {
        d: `M${cx - 4} ${cy - 4}L${cx + 4} ${cy + 4}M${cx - 4} ${cy + 4}L${cx + 4} ${cy - 4}`,
        fill: 'none',
        stroke: 'var(--failed)',
        'stroke-width': 2,
        'stroke-linecap': 'round',
      }));
    }
    // The hit area is the whole column, so short bars are as easy to hover as tall ones.
    const hit = svg('rect', {
      x: BARS.left + slot * index,
      y: BARS.top,
      width: slot,
      height: plotHeight,
      fill: 'transparent',
    });
    hit.addEventListener('mousemove', (event) => showBar(index, event.clientX, event.clientY));
    bar.append(hit);
    // With very many tests the IDs would overlap, so they stay in the tooltip and the list.
    if (slot >= BARS.minLabelSlot && test.tag) {
      const labelX = x + barWidth / 2 + 4;
      const labelY = baseline + 8;
      bar.append(svg('text', {
        class: 'bar-label',
        x: labelX,
        y: labelY,
        'text-anchor': 'end',
        transform: `rotate(-90 ${labelX} ${labelY})`,
      }, test.tag));
    }
    group.append(bar);
    if (test.status !== 'skipped' && (slowest === null || seconds > slowest.seconds)) {
      slowest = { seconds, x: x + barWidth / 2, height, status: test.status };
    }
  });
  chart.append(group);

  if (slowest !== null) {
    // The one direct label: the slowest test, kept inside the plot at the right edge.
    const onRight = slowest.x > BARS.left + plotWidth - 60;
    chart.append(svg('text', {
      class: 'end-label',
      x: onRight ? slowest.x + barWidth / 2 : slowest.x,
      y: baseline - slowest.height - (slowest.status === 'failed' ? 18 : 6),
      'text-anchor': onRight ? 'end' : 'middle',
    }, `${slowest.seconds.toFixed(1)} s`));
  }

  if (tests.length === 0) {
    chart.append(svg('text', {
      x: BARS.left, y: baseline + 20,
    }, 'Bars appear as tests finish'));
  }

  container.replaceChildren(chart);
  container.setAttribute('aria-label',
      `Time per test for ${tests.length} finished tests. The list below has the same values.`);
  element('bars-legend').replaceChildren(
      ...['passed', 'failed', 'skipped'].map(
          (status) => legendItem(STATUSES[status].colour, STATUSES[status].label),
      ),
  );
}

function barTooltip(test) {
  const row = html('div', 'tooltip-row');
  row.append(
      statusIcon(test.status),
      test.passedOnRetry ? 'Passed on retry' : STATUSES[test.status].label,
      ` · ${formatDuration(test.durationMs)}`,
  );
  return [html('div', '', test.tag ? `${test.tag}: ${test.name}` : test.name), row];
}

function showBar(index, x, y) {
  const tests = finishedTests();
  if (index < 0 || index >= tests.length) {
    return;
  }
  if (activeBar !== index) {
    activeBar = index;
    renderBars();
  }
  showTooltip(barTooltip(tests[index]), x, y);
}

function clearActiveBar() {
  activeBar = null;
  hideTooltip();
  renderBars();
}

function renderFilters(tally, total) {
  const buttons = FILTERS.map((name) => {
    const count = name === 'all' ? total : tally[name];
    const label = name === 'all' ? 'All' : STATUSES[name].label;
    const button = html('button', 'filter', `${label} ${count}`);
    button.type = 'button';
    button.setAttribute('aria-pressed', String(filter === name));
    button.addEventListener('click', () => {
      filter = name;
      queueRender();
    });
    return button;
  });
  element('filters').replaceChildren(...buttons);
}

function renderTests() {
  const tests = [...run.tests.values()]
    .filter((test) => filter === 'all' || test.status === filter)
    .sort((a, b) => STATUSES[a.status].rank - STATUSES[b.status].rank || a.order - b.order);

  const rows = tests.map((test) => {
    const row = html('tr');
    const nameCell = html('td', '', test.name);
    if (test.message && test.status !== 'passed') {
      const failureText = test.location ? `${test.message}\n    at ${test.location}` : test.message;
      nameCell.append(html('pre', 'failure', failureText));
    }
    const result = html('span', 'result');
    result.append(
        statusIcon(test.status),
        test.passedOnRetry ? 'Passed on retry' : STATUSES[test.status].label,
    );
    const resultCell = html('td');
    resultCell.append(result);
    const finishedAt = test.finishedAt === undefined || test.status === 'running'
      ? ''
      : formatClock((test.finishedAt - run.startedAt) / 1000);
    row.append(
        html('td', 'tag', test.tag ?? ''),
        nameCell,
        html('td', 'group', test.group),
        resultCell,
        html('td', 'number', test.status === 'running' ? '' : formatDuration(test.durationMs)),
        html('td', 'number', finishedAt),
    );
    return row;
  });
  element('tests').replaceChildren(...rows);
  element('no-match').hidden = rows.length > 0;
}

// ---------- Setup ----------

function setUpChartInteraction() {
  const container = element('chart');
  container.addEventListener('mouseleave', clearActiveBar);
  container.addEventListener('blur', clearActiveBar);
  container.addEventListener('keydown', (event) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') {
      return;
    }
    event.preventDefault();
    const count = finishedTests().length;
    if (count === 0) {
      return;
    }
    const move = event.key === 'ArrowRight' ? 1 : -1;
    const next = activeBar === null ? 0 : Math.min(count - 1, Math.max(0, activeBar + move));
    const { slot } = barGeometry();
    const box = container.getBoundingClientRect();
    showBar(next, box.left + BARS.left + slot * next, box.top + BARS.top + 40);
  });
}

function setUpPaletteSwitch() {
  const toggle = element('palette-switch');
  const apply = (useColourBlind) => {
    if (useColourBlind) {
      document.documentElement.dataset.palette = 'colour-blind';
    } else {
      delete document.documentElement.dataset.palette;
    }
  };
  let saved = null;
  try {
    saved = localStorage.getItem(PALETTE_KEY);
  } catch {
    // Storage can be blocked; the switch still works for this visit.
  }
  toggle.checked = saved === 'colour-blind';
  apply(toggle.checked);
  toggle.addEventListener('change', () => {
    apply(toggle.checked);
    try {
      localStorage.setItem(PALETTE_KEY, toggle.checked ? 'colour-blind' : 'standard');
    } catch {
      // See above.
    }
  });
}

// ---------- Run history ----------

const summaries = new Map();
// Each run's file time when it was last read: a run is read again only after its file changed.
const readAt = new Map();

function summarize(id, eventsText) {
  // Reuses the same rules as the live view, so counts here and on a run's page always match.
  const saved = run;
  run = newRun();
  eventsText.split('\n').filter((line) => line.trim() !== '').forEach((line) => {
    try {
      applyEvent(JSON.parse(line));
    } catch {
      // The last line can be cut short if a run was stopped mid-write; the rest is still valid.
    }
  });
  const summary = {
    id,
    tally: counts(),
    total: run.tests.size,
    state: run.state,
    startedAt: run.startedAt,
    seconds: run.finishedAt === null ? null : (run.finishedAt - run.startedAt) / 1000,
  };
  run = saved;
  return summary;
}

function historyResult(summary) {
  if (summary.state !== 'finished') {
    return { icon: 'running', text: 'In progress or stopped' };
  }
  return summary.tally.failed > 0
    ? { icon: 'failed', text: `${summary.tally.failed} failed` }
    : { icon: 'passed', text: 'No failures' };
}

function renderHistory(ids) {
  const rows = ids.filter((id) => summaries.has(id)).map((id) => {
    const summary = summaries.get(id);
    const result = historyResult(summary);
    const row = html('tr');
    const link = html('a', '', summary.startedAt === null ? id : formatDate(summary.startedAt));
    link.href = `/?run=${encodeURIComponent(id)}`;
    const startedCell = html('td');
    startedCell.append(link);
    const resultSpan = html('span', 'result');
    resultSpan.append(statusIcon(result.icon), result.text);
    const resultCell = html('td');
    resultCell.append(resultSpan);
    row.append(
        startedCell,
        resultCell,
        html('td', 'number', summary.tally.passed),
        html('td', 'number', summary.tally.failed),
        html('td', 'number', summary.tally.skipped),
        html('td', 'number', summary.total),
        html('td', 'number', summary.seconds === null ? '' : formatClock(summary.seconds)),
    );
    return row;
  });
  element('history-rows').replaceChildren(...rows);
  element('history-note').textContent = rows.length === 0
    ? 'No saved runs yet. Runs appear here as soon as they start.'
    : 'Open any run to see its results. Each run keeps its own link.';
}

async function loadHistory() {
  try {
    const saved = await (await fetch('/api/runs')).json();
    const ids = saved.map((savedRun) => savedRun.id);
    // A finished run never changes. A run that was stopped never finishes, but its file stops
    // changing, so it is not downloaded again every few seconds either.
    const pending = saved.filter(({ id, updatedAt }) =>
      summaries.get(id)?.state !== 'finished' && readAt.get(id) !== updatedAt);
    await Promise.all(pending.map(async ({ id, updatedAt }) => {
      const response = await fetch(`/api/runs/${encodeURIComponent(id)}/events`);
      if (response.ok) {
        summaries.set(id, summarize(id, await response.text()));
        readAt.set(id, updatedAt);
      }
    }));
    renderHistory(ids);
  } catch {
    element('history-note').textContent = 'Could not load the saved runs. Retrying…';
  }
}

function startHistoryView() {
  element('empty').hidden = true;
  element('run-state').hidden = true;
  element('history').hidden = false;
  element('nav-history').setAttribute('aria-current', 'page');
  loadHistory();
  setInterval(loadHistory, 5000);
}

async function startRunView() {
  if (pinnedRun === null) {
    element('nav-latest').setAttribute('aria-current', 'page');
  } else {
    const response = await fetch(`/api/runs/${encodeURIComponent(pinnedRun)}/events`);
    if (!response.ok) {
      element('empty').replaceChildren(
          html('h2', '', 'Run not found'),
          html('p', '', 'This run was deleted, or the link is wrong. Saved runs are in Run history.'),
      );
      element('run-state').hidden = true;
      return;
    }
  }
  render();
  connect();
}

setUpPaletteSwitch();
setUpChartInteraction();
window.addEventListener('resize', queueRender);
// Between events only the clock moves, so buttons and the charts keep keyboard focus.
setInterval(() => {
  if (run.state === 'running' && run.tests.size > 0) {
    renderElapsed();
  }
}, 1000);
if (isHistoryView) {
  startHistoryView();
} else {
  startRunView();
}

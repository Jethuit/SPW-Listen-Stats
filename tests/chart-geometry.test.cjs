// Dependency-free coordinate regression checks; run with: node tests/chart-geometry.test.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../preview/app.js'), 'utf8');
const elements = new Map();
let displayScale = 1;
function element(tag = 'div') {
  return {
    tag, dataset: {}, attributes: {}, style: {}, children: [], options: new Map(),
    classList: { add() {}, remove() {} }, clientWidth: 1080, clientHeight: 258,
    append(...children) { this.children.push(...children); },
    replaceChildren(...children) { this.children = children; },
    setAttribute(key, value) { this.attributes[key] = value; },
    addEventListener() {},
    querySelector(selector) {
      if (!this.options.has(selector)) this.options.set(selector, element());
      return this.options.get(selector);
    },
    getContext() { return { measureText: text => ({ width: text.length * 6.5 }) }; },
    getBoundingClientRect() {
      return this === get('trend-chart')
        ? { left: 80, top: 100, width: this.clientWidth * displayScale, height: this.clientHeight * displayScale }
        : { left: 0, top: 0, width: 180, height: 80 };
    }
  };
}
function get(id) {
  if (!elements.has(id)) elements.set(id, element());
  return elements.get(id);
}
const context = {
  document: { getElementById: get, createElement: element },
  matchMedia: () => ({ matches: false }), localStorage: { getItem: () => null },
  getComputedStyle: () => ({ fontFamily: 'Segoe UI' }), Intl, innerWidth: 2200, innerHeight: 1000
};
const boot = source.indexOf("  document.querySelectorAll('[data-chart-type]').forEach(button => button.addEventListener");
assert(boot > 0);
vm.runInNewContext(source.slice(0, boot) + `
  globalThis.model = {
    renderChart, showChartPoint, axisNumber, getRange,
    points: () => chartPoints, bounds: () => chartBounds,
    configure(type, scenario) {
      chartType = type; range = scenario.range; selectedDay = '2026-01-03';
      customStart = scenario.start; customEnd = scenario.end;
    },
    fixture(kind) {
      records = [];
      if (kind !== 'empty') {
        let index = 0;
        for (let date = '2024-01-01'; date <= TODAY; date = addDays(date, 1), index++) {
          const ms = kind === 'tiny' ? 24 : kind === 'uniform' ? 5400000 : index % 11 === 0 ? 86400000 : 0;
          const hourlyMs = kind === 'tiny' ? Array(24).fill(1) : distribute(ms, Array.from({length: 24}, (_, h) => h + 1));
          records.push({date, ms, hourlyMs});
        }
      }
      byDate = new Map(records.map(record => [record.date, record]));
    }
  };
})();`, context);
const model = context.model;
const scenarios = [
  ...['7', '30', 'month', 'today', 'selected'].map(range => ({ range })),
  ...[
    ['2026-01-03', '2026-01-03'], ['2026-01-31', '2026-02-01'],
    ['2026-01-01', '2026-01-08'], ['2026-01-01', '2026-01-14'],
    ['2025-12-29', '2026-01-08'], ['2024-02-28', '2024-03-01'],
    ['2024-10-18', '2026-10-18']
  ].map(([start, end]) => ({ range: 'custom', start, end }))
];
function attrs(markup) {
  return Object.fromEntries([...markup.matchAll(/([\w-]+)="([^"]*)"/g)].map(match => [match[1], match[2]]));
}
function near(actual, expected, message) {
  assert(Math.abs(actual - expected) < 1e-6, `${message}: ${actual} != ${expected}`);
}
let cases = 0;
for (const fixture of ['empty', 'tiny', 'uniform', 'sparse']) {
  model.fixture(fixture);
  for (const scenario of scenarios) for (const granularity of ['day', 'week']) {
    for (const type of ['bar', 'line', 'curve']) for (const width of [220, 280, 360, 499, 500, 760, 1080, 1651]) {
      const svg = get('trend-chart');
      svg.clientWidth = width; svg.clientHeight = width < 600 ? 225 : 258;
      get('granularity-select').value = granularity;
      model.configure(type, scenario); model.renderChart();
      const markup = svg.innerHTML, points = model.points(), bounds = model.bounds();
      const [start, end] = model.getRange();
      const days = Math.round((new Date(end + 'T12:00:00') - new Date(start + 'T12:00:00')) / 86400000) + 1;
      const hourly = days === 1;
      assert.equal(get('granularity-control').hidden, days < 14);
      assert.equal(points.length, hourly ? 24 : days < 14 || granularity === 'day' ? days : Math.ceil(days / 7));
      assert.equal(get('granularity-select').value, granularity, 'automatic view must preserve the long-range preference');
      const total = points.reduce((sum, point) => sum + point.ms, 0);
      assert(!markup.includes('NaN') && !markup.includes('Infinity'));
      assert.equal(get('chart-empty').hidden, total > 0);
      assert.equal(markup.includes('class="chart-average"'), !hourly && total > 0);
      assert(points.every(point => point.x >= bounds.left && point.x <= bounds.right && point.y >= bounds.top - 1e-8 && point.y <= bounds.bottom));

      // Every label uses the same x coordinate as the corresponding data point, including endpoints.
      const labels = [...markup.matchAll(/<text class="chart-tick" data-tick-index="[^"]+"[^>]*>([^<]+)<\/text>/g)];
      assert(labels.length >= 1 && labels.length <= 7);
      const selected = labels.map(match => ({ ...attrs(match[0]), text: match[1] }));
      assert.equal(Number(selected[0]['data-tick-index']), 0);
      assert.equal(Number(selected.at(-1)['data-tick-index']), points.length - 1);
      for (let i = 0; i < selected.length; i++) {
        const label = selected[i], point = points[Number(label['data-tick-index'])];
        assert.equal(label['text-anchor'], 'middle'); near(Number(label.x), point.x, 'label center');
        assert.equal(label.text, point.label);
        const halfWidth = label.text.length * 6.5 / 2;
        assert(Number(label.x) - halfWidth >= 0 && Number(label.x) + halfWidth <= bounds.width);
        if (i) assert(Number(label.x) - Number(selected[i - 1].x) >= (label.text.length + selected[i - 1].text.length) * 6.5 / 2 + 15.99);
      }
      // Bar centers, line endpoints and hover markers all share those coordinates.
      const bars = [...markup.matchAll(/<rect class="chart-bar"[^>]+\/>/g)].map(match => attrs(match[0]));
      assert.equal(bars.length, type === 'bar' ? points.filter(point => point.ms > 0).length : 0);
      for (const bar of bars) {
        const point = points[Number(bar['data-point-index'])];
        near(Number(bar.x) + Number(bar.width) / 2, point.x, 'bar center');
        near(Number(bar.y), point.y, 'bar top');
        near(Number(bar.y) + Number(bar.height), bounds.bottom, 'bar baseline');
      }
      if (type !== 'bar') {
        const curve = markup.match(/<path class="chart-line" d="([^"]+)"/)[1];
        const endpoints = [...curve.matchAll(/[ML]([^MLC]+)|C([^MLC]+)/g)].map(match => {
          const numbers = (match[1] || match[2]).trim().split(/[ ,]+/).map(Number);
          return numbers.slice(-2);
        });
        assert.equal(endpoints.length, points.length);
        endpoints.forEach(([x, y], i) => {
          assert(Math.abs(x - points[i].x) <= .00051 && Math.abs(y - points[i].y) <= .00051);
        });
      }
      if (hourly) {
        assert.equal(points.length, 24); assert.equal(selected.at(-1).text, '23:00');
        assert(!svg.attributes['aria-label'].includes('小时均值'));
      }
      const yTicks = [...markup.matchAll(/<text class="chart-tick" data-axis-value="[^"]+"[^>]*>([^<]+)<\/text>/g)];
      assert.equal(new Set(yTicks.map(match => match[1])).size, yTicks.length);

      if (total > 0) for (const scale of [0.75, 1.25]) {
        displayScale = scale;
        const rect = svg.getBoundingClientRect();
        for (const index of [...new Set([0, Math.floor(points.length / 2), points.length - 1])]) {
          const point = points[index];
          model.showChartPoint({ clientX: rect.left + point.x / bounds.width * rect.width });
          near(Number(get('chart-crosshair').attributes.x1), point.x, 'hover crosshair');
          near(Number(get('chart-point').attributes.cx), point.x, 'hover marker');
          assert(get('tooltip').innerHTML.includes(point.fullLabel));
          const expectedLeft = Math.max(10, Math.min(context.innerWidth - 190, rect.left + point.x / bounds.width * rect.width - 90));
          near(parseFloat(get('tooltip').style.left), expectedLeft, 'scaled tooltip anchor');
          const anchorY = rect.top + point.y / bounds.height * rect.height;
          const desiredTop = anchorY - 92 < 10 ? anchorY + 12 : anchorY - 92;
          near(parseFloat(get('tooltip').style.top), Math.max(10, Math.min(desiredTop, context.innerHeight - 90)), 'scaled tooltip height');
          if (index > 0) {
            const boundary = bounds.banded ? bounds.left + index / points.length * (bounds.right - bounds.left) : (points[index - 1].x + point.x) / 2;
            for (const [offset, expected] of [[-.0001, points[index - 1]], [.0001, point]]) {
              model.showChartPoint({ clientX: rect.left + (boundary + offset) / bounds.width * rect.width });
              near(Number(get('chart-point').attributes.cx), expected.x, 'hover interval boundary');
            }
          }
        }
      }
      displayScale = 1;
      cases++;
    }
  }
}
assert.notEqual(model.axisNumber(0.000005, 0.000005), model.axisNumber(0.00001, 0.000005));
model.fixture('uniform');
get('granularity-select').value = 'week';
for (const [end, count, hidden] of [['2026-01-01', 24, true], ['2026-01-13', 13, true], ['2026-01-14', 2, false], ['2026-01-30', 5, false]]) {
  model.configure('bar', {range: 'custom', start: '2026-01-01', end});
  model.renderChart();
  assert.equal(model.points().length, count);
  assert.equal(get('granularity-control').hidden, hidden);
  assert.equal(get('granularity-select').value, 'week');
  const option = get('range-select').querySelector('[value="custom"]');
  assert.equal(option.textContent, end === '2026-01-01' ? '2026年1月1日' : `2026/01/01 - ${end.replaceAll('-', '/')}`);
}
get('granularity-select').value = 'day'; model.renderChart();
assert.equal(model.points().length, 30);
console.log(`Passed ${cases} coordinate scenarios plus single/short/long range transitions: auto hourly/daily views, long-range day/week preferences, actual date labels, all chart types, 8 widths, 4 data distributions and scaled hover positions.`);

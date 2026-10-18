// Run with: node tests/heatmap-layout.test.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../preview/app.js'), 'utf8');
const css = fs.readFileSync(path.join(__dirname, '../preview/styles.css'), 'utf8');

function element() {
  return {
    children: [], dataset: {}, attributes: {}, className: '',
    style: { setProperty(key, value) { this[key] = value; } },
    get classList() {
      return {
        add: (...names) => { this.className = [...new Set([...this.className.split(/\s+/).filter(Boolean), ...names])].join(' '); },
        toggle: (name, active) => {
          const names = new Set(this.className.split(/\s+/).filter(Boolean));
          if (active) names.add(name); else names.delete(name);
          this.className = [...names].join(' ');
        }
      };
    },
    append(...children) { this.children.push(...children); },
    replaceChildren(...children) { this.children = children; },
    setAttribute(key, value) { this.attributes[key] = value; }, addEventListener() {}
  };
}
const ids = new Map(), layout = element();
const get = id => {
  if (!ids.has(id)) ids.set(id, element());
  return ids.get(id);
};
const context = {
  document: { getElementById: get, createElement: element, createDocumentFragment: element,
    querySelector: () => layout, querySelectorAll: () => [] },
  matchMedia: () => ({ matches: false }), localStorage: { getItem: () => null }, Intl
};
const boot = source.indexOf("  document.querySelectorAll('[data-chart-type]').forEach(button => button.addEventListener");
vm.runInNewContext(source.slice(0, boot) + '\nglobalThis.model = { setHeatmapMode, heatmapCalendar };\n})();', context);
const model = context.model;
let checked = 0;
for (let year = 2000; year <= 2026; year++) {
  get('year-select').value = String(year);
  model.setHeatmapMode('day');
  const daily = get('heatmap').children[0].children;
  const dailyMonths = get('month-labels').children.map(label => label.style.gridColumn);
  const columns = layout.style['--weeks'];
  model.setHeatmapMode('week');
  const weeklyColumns = get('heatmap').children[0].children;
  const weekly = weeklyColumns.flatMap(column => column.children);
  assert.equal(weeklyColumns.length, columns);
  assert.equal(weekly.length, daily.length);
  assert.deepEqual(get('month-labels').children.map(label => label.style.gridColumn), dailyMonths);
  for (let index = 0; index < daily.length; index++) {
    assert.equal(weekly[index].className.includes('padding'), daily[index].className.includes('padding'));
    assert.equal(weekly[index].dataset.date, daily[index].dataset.date, `date mismatch: ${year}, slot ${index}`);
    if (weekly[index].className.includes('padding')) {
      assert.equal(weekly[index].dataset.level, 0);
      assert(!weekly[index].className.includes('filled'));
    }
  }
  const expected = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 366 : 365;
  assert.equal(daily.filter(cell => !cell.className.includes('padding')).length, expected);
  assert.equal(weekly.filter(cell => !cell.className.includes('padding')).length, expected);
  assert(weeklyColumns.every(column => column.children.length === 7));
  if (year === 2026) {
    assert.deepEqual(weekly.slice(0, 7).map(cell => cell.className.includes('padding')), [true, true, true, false, false, false, false]);
    assert.deepEqual(weekly.slice(-7).map(cell => cell.className.includes('padding')), [false, false, false, false, true, true, true]);
  }
  checked++;
}
assert.equal(model.heatmapCalendar(2012).weekCount, 54);
assert(css.includes('.day-cell.padding, .week-block.padding { visibility: hidden; }'));
console.log(`Passed ${checked} years: daily/weekly slots, dates, padding masks, 365/366 visible cells, month labels and 54-week layout match.`);

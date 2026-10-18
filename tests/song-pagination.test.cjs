// Run with: node tests/song-pagination.test.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../preview/app.js'), 'utf8');
const html = fs.readFileSync(path.join(__dirname, '../preview/index.html'), 'utf8');

function element(tag) {
  return {
    tag, children: [], dataset: {}, attributes: {},
    append(...children) { this.children.push(...children); },
    replaceChildren(...children) { this.children = children; },
    setAttribute(key, value) { this.attributes[key] = value; },
    addEventListener() {}, scrollIntoView() { this.scrolled = true; }, focus() { this.focused = true; },
    querySelector() { return this.children[0]?.children.find(child => child.attributes['aria-current'] === 'page'); }
  };
}
const ids = new Map();
const get = id => {
  if (!ids.has(id)) ids.set(id, element('div'));
  return ids.get(id);
};
const navs = [get('song-pagination-top'), element('nav')];
get('song-search').value = '';
const context = {
  document: {
    getElementById: get, createElement: element, createDocumentFragment: () => element('fragment'),
    createTextNode: text => ({ text }), querySelectorAll: selector => selector === '[data-song-pagination]' ? navs : []
  },
  matchMedia: () => ({ matches: false }), localStorage: { getItem: () => null }, Intl
};
const boot = source.indexOf("  document.querySelectorAll('[data-chart-type]').forEach(button => button.addEventListener");
vm.runInNewContext(source.slice(0, boot) + `
  globalThis.model = {
    renderSongs, changeSongPage, searchSongs,
    load(value) { tracks = value; songPage = 1; },
    page: () => songPage
  };
})();`, context);
const model = context.model;
const rows = () => get('song-table').children[0].children;
const fixture = count => Array.from({ length: count }, (_, index) => ({
  id: `track-${index + 1}`, title: `歌曲${String(index + 1).padStart(5, '0')}`,
  artist: `歌手${index % 7}`, album: '测试专辑', plays: count - index
}));
let checkedPages = 0;
for (const count of [0, 1, 12, 49, 50, 51, 100, 101, 137, 500, 10000]) {
  model.load(fixture(count)); get('song-search').value = ''; model.renderSongs();
  const totalPages = Math.max(1, Math.ceil(count / 50));
  const seen = [];
  for (let page = 1; page <= totalPages; page++) {
    model.changeSongPage(page);
    if (count) {
      assert.equal(rows().length, Math.min(50, count - (page - 1) * 50));
      rows().forEach((row, index) => {
        assert.equal(row.children[0].textContent, String((page - 1) * 50 + index + 1));
        assert.equal(row.children[0].className, 'song-rank');
        assert.equal(row.children[1].children[0].children[0].tag, 'img');
        seen.push(row.dataset.trackId);
      });
    } else {
      assert.equal(rows()[0].children[0].colSpan, 4);
      assert(!get('song-page-summary').textContent.includes('1–0'));
    }
    assert(get('song-page-summary').textContent.includes('每页 50 首'));
    for (const nav of navs) {
      assert.equal(nav.hidden, totalPages <= 1);
      const buttons = nav.children[0].children.filter(child => child.tag === 'button');
      assert.equal(buttons[0].disabled, page === 1);
      assert.equal(buttons.at(-1).disabled, page === totalPages);
      assert.equal(buttons.filter(button => button.attributes['aria-current'] === 'page').length, 1);
      assert.equal(buttons.find(button => button.attributes['aria-current'] === 'page').textContent, String(page));
      assert(buttons.length <= 9);
    }
    checkedPages++;
  }
  assert.equal(new Set(seen).size, count);
}
model.load(fixture(500)); get('song-search').value = ''; model.renderSongs(); model.changeSongPage(10);
assert.equal(model.page(), 10); assert.equal(rows()[0].children[0].textContent, '451');
assert(get('ranking-page').scrolled);
assert(navs[0].querySelector().focused);
get('song-search').value = '歌曲００１２０'; model.searchSongs();
assert.equal(model.page(), 1); assert.equal(rows().length, 1);
assert.equal(rows()[0].children[0].textContent, '120'); assert(navs.every(nav => nav.hidden));
get('song-search').value = '歌手0'; model.searchSongs();
assert.equal(rows().length, 50); model.changeSongPage(2); assert.equal(rows().length, 22);
assert.equal(rows()[0].children[0].textContent, '351');
get('song-search').value = '没有这首歌'; model.searchSongs();
assert.equal(model.page(), 1); assert.equal(rows()[0].children[0].textContent, '没有找到匹配的歌曲');
get('song-search').value = ''; model.searchSongs(); assert.equal(rows()[0].children[0].textContent, '1');
model.changeSongPage(9999); assert.equal(model.page(), 10);
assert(html.includes('class="song-rank">排名</th>'));
assert(source.includes('records = imported.records; tracks = imported.tracks; songPage = 1;'));
console.log(`Passed ${checkedPages} pages across 0–10,000 tracks; 50-row limit, both pagers, ranks, global search, page bounds and empty states.`);

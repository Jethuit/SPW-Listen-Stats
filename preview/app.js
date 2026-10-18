(() => {
  'use strict';

  // Fixed demonstration date: these records never represent the user's real listening history.
  const TODAY = '2026-10-18';
  const FORMAT = 'spw-listening-stats-preview';
  const $ = id => document.getElementById(id);
  const number = new Intl.NumberFormat('zh-CN');
  const parseDate = key => new Date(`${key}T12:00:00`);
  const dateKey = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  const addDays = (key, count) => { const d = parseDate(key); d.setDate(d.getDate() + count); return dateKey(d); };
  const dateLabel = key => `${parseDate(key).getMonth() + 1}月${parseDate(key).getDate()}日`;
  const shortDate = key => `${parseDate(key).getMonth() + 1}/${parseDate(key).getDate()}`;
  const safeStorage = {
    get(key) { try { return localStorage.getItem(key); } catch { return null; } },
    set(key, value) { try { localStorage.setItem(key, value); } catch { /* Local files may disable storage. */ } }
  };
  let theme = safeStorage.get('spw-preview-theme') || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  let selectedDay = null;
  let range = '30';
  let customStart = addDays(TODAY, -29);
  let customEnd = TODAY;
  let chartPoints = [];
  let chartBounds = null;
  let toastTimer;

  function random(seed) {
    let state = seed >>> 0;
    return () => { state = (Math.imul(state, 1664525) + 1013904223) >>> 0; return state / 4294967296; };
  }

  function distribute(total, weights) {
    const sum = weights.reduce((a, b) => a + b, 0);
    const raw = weights.map(weight => total * weight / sum);
    const result = raw.map(Math.floor);
    const order = raw.map((value, index) => ({ index, fraction: value - result[index] })).sort((a, b) => b.fraction - a.fraction);
    for (let remaining = total - result.reduce((a, b) => a + b, 0), i = 0; i < remaining; i++) result[order[i].index]++;
    return result;
  }

  function makeDemoRecords() {
    const records = [];
    for (let key = '2025-01-01'; key <= TODAY; key = addDays(key, 1)) {
      const day = parseDate(key);
      const rng = random(Number(key.replaceAll('-', '')) ^ 0x5eed);
      const month = day.getMonth();
      const probability = [0.55, 0.8, 0.65, 0.94, 0.96, 0.65, 0.3, 0.23, 0.68, 0.8, 0.5, 0.7][month];
      let active = rng() < probability;
      if (key >= '2026-10-12') active = true;
      if (key === '2026-10-11') active = false;
      if (!active) continue;
      const minutes = Math.round(12 + Math.pow(rng(), 1.4) * 175 + (month === 3 || month === 4 ? 35 : 0));
      const ms = minutes * 60_000;
      const weights = Array.from({ length: 24 }, (_, h) => {
        const morning = Math.exp(-((h - 8) ** 2) / 3) * 0.45;
        const midday = Math.exp(-((h - 13) ** 2) / 5) * 0.28;
        const evening = Math.exp(-((h - 21) ** 2) / 5);
        return (morning + midday + evening + 0.004) * (0.35 + rng());
      });
      records.push({ date: key, ms, hourlyMs: distribute(ms, weights) });
    }
    return records;
  }

  let records = makeDemoRecords();
  // Fictional song metadata for the preview. Counts belong to individual track IDs.
  let tracks = [
    { id: 'demo-01', title: '晚风来信', artist: '林间电台', album: '日落以后', plays: 156 },
    { id: 'demo-02', title: '蓝色车站', artist: '远山乐队', album: '沿途', plays: 132 },
    { id: 'demo-03', title: '雨停之前', artist: '夏予', album: '城市慢拍', plays: 117 },
    { id: 'demo-04', title: '凌晨两点', artist: '白昼航线', album: '夜行', plays: 93 },
    { id: 'demo-05', title: '玻璃海', artist: '海岸线', album: '潮汐之间', plays: 88 },
    { id: 'demo-06', title: '走过四季', artist: '林间电台', album: '日落以后', plays: 75 },
    { id: 'demo-07', title: '把灯留着', artist: '夏予', album: '城市慢拍', plays: 64 },
    { id: 'demo-08', title: '慢慢靠近', artist: '远山乐队', album: '沿途', plays: 51 },
    { id: 'demo-09', title: '日光倾斜', artist: '白昼航线', album: '日间漫步', plays: 43 },
    { id: 'demo-10', title: '晚风来信', artist: '海岸线', album: '海边现场', plays: 28 },
    { id: 'demo-11', title: '下一个路口', artist: '木叶', album: '散步集', plays: 16 },
    { id: 'demo-12', title: '微光', artist: '木叶', album: '散步集', plays: 7 }
  ];
  let byDate = new Map(records.map(record => [record.date, record]));
  const emptyRecord = date => ({ date, ms: 0, hourlyMs: Array(24).fill(0) });
  const recordAt = date => byDate.get(date) || emptyRecord(date);

  function duration(ms, compact = false) {
    const minutes = Math.floor(ms / 60_000);
    const hours = Math.floor(minutes / 60);
    if (hours) return compact ? `${number.format(hours)}小时${minutes % 60 ? `${minutes % 60}分` : ''}` : `${number.format(hours)} 小时${minutes % 60 ? ` ${minutes % 60} 分钟` : ''}`;
    return minutes ? `${minutes}${compact ? '分钟' : ' 分钟'}` : ms > 0 ? '不足 1 分钟' : '0 分钟';
  }

  function renderSummary() {
    const totalMs = records.reduce((sum, record) => sum + record.ms, 0);
    const hours = Math.floor(totalMs / 3_600_000);
    $('total-time').innerHTML = hours ? `${number.format(hours)}<small>小时</small>${Math.floor(totalMs / 60_000) % 60}<small>分</small>` : `${Math.floor(totalMs / 60_000)}<small>分钟</small>`;
    const active = records.filter(record => record.ms > 0).map(record => record.date).sort();
    $('active-days').innerHTML = `${number.format(active.length)}<small>天</small>`;
    let longest = 0, streak = 0, previous;
    for (const date of active) {
      streak = previous && addDays(previous, 1) === date ? streak + 1 : 1;
      longest = Math.max(longest, streak);
      previous = date;
    }
    let current = 0;
    let currentDate = recordAt(TODAY).ms > 0 ? TODAY : addDays(TODAY, -1);
    while (recordAt(currentDate).ms > 0) { current++; currentDate = addDays(currentDate, -1); }
    $('longest-streak').innerHTML = `${longest}<small>天</small>`;
    $('current-streak').innerHTML = `${current}<small>天</small>`;
    $('current-streak').title = '连续听歌天数；当天尚未听歌时，延续到昨天的连续记录。';
    const peak = [...records].sort((a, b) => b.ms - a.ms)[0];
    $('peak-day').textContent = peak ? `${peak.date.slice(0, 4)}年${dateLabel(peak.date)} · ${duration(peak.ms)}` : '暂无记录';
    $('daily-average').textContent = active.length ? duration(totalMs / active.length) : '暂无记录';
    const hourly = Array.from({ length: 24 }, (_, h) => records.reduce((sum, record) => sum + record.hourlyMs[h], 0));
    const peakHour = hourly.indexOf(Math.max(...hourly));
    $('favorite-hours').textContent = totalMs ? `${String(peakHour).padStart(2, '0')}:00 — ${String(peakHour + 1).padStart(2, '0')}:00` : '暂无记录';
  }

  function setupYears() {
    const previous = $('year-select').value;
    const years = [...new Set([TODAY.slice(0, 4), ...records.map(record => record.date.slice(0, 4))])].sort().reverse();
    $('year-select').replaceChildren(...years.map(year => new Option(year, year)));
    $('year-select').value = years.includes(previous) ? previous : years[0];
  }

  function levelFor(record) {
    const value = record.ms / 60_000;
    const cutoffs = [0, 30, 75, 135];
    return value <= 0 ? 0 : value <= cutoffs[1] ? 1 : value <= cutoffs[2] ? 2 : value <= cutoffs[3] ? 3 : 4;
  }

  function renderHeatmap() {
    const year = Number($('year-select').value);
    const first = `${year}-01-01`, last = `${year}-12-31`;
    const start = addDays(first, -((parseDate(first).getDay() + 6) % 7));
    const end = addDays(last, 6 - ((parseDate(last).getDay() + 6) % 7));
    const dates = [];
    for (let key = start; key <= end; key = addDays(key, 1)) dates.push(key);
    const weeks = dates.length / 7;
    document.querySelector('.heatmap-layout').style.setProperty('--weeks', weeks);
    const monthLabels = $('month-labels');
    monthLabels.replaceChildren();
    for (let month = 1; month <= 12; month++) {
      const key = `${year}-${String(month).padStart(2, '0')}-01`;
      const label = document.createElement('span');
      label.textContent = `${month}月`;
      label.style.gridColumn = `${Math.floor(dates.indexOf(key) / 7) + 1} / span 2`;
      monthLabels.append(label);
    }
    const fragment = document.createDocumentFragment();
    const focusDate = selectedDay?.startsWith(String(year)) ? selectedDay : year === Number(TODAY.slice(0, 4)) ? TODAY : last;
    for (const key of dates) {
      const button = document.createElement('button');
      button.className = 'day-cell';
      if (key < first || key > last) {
        button.classList.add('padding'); button.disabled = true; button.setAttribute('aria-hidden', 'true');
      } else {
        button.dataset.date = key;
        button.dataset.level = levelFor(recordAt(key));
        button.tabIndex = key === focusDate ? 0 : -1;
        if (key > TODAY) { button.disabled = true; button.classList.add('future'); }
        const record = recordAt(key);
        button.setAttribute('aria-label', `${key}，听歌 ${duration(record.ms)}${key > TODAY ? '，未来日期' : ''}`);
        button.setAttribute('aria-pressed', String(key === selectedDay));
        if (key === selectedDay) button.classList.add('selected');
        button.addEventListener('mouseenter', () => showRecordTooltip(key, button.getBoundingClientRect()));
        button.addEventListener('mouseleave', hideTooltip);
        button.addEventListener('focus', () => showRecordTooltip(key, button.getBoundingClientRect()));
        button.addEventListener('blur', hideTooltip);
        button.addEventListener('click', () => selectDay(key));
        button.addEventListener('keydown', event => {
          const offsets = { ArrowLeft: -7, ArrowRight: 7, ArrowUp: -1, ArrowDown: 1 };
          if (Object.hasOwn(offsets, event.key)) {
            event.preventDefault();
            const next = $('heatmap').querySelector(`[data-date="${addDays(key, offsets[event.key])}"]:not(:disabled)`);
            if (next) { button.tabIndex = -1; next.tabIndex = 0; next.focus(); }
          }
          if (event.key === 'Escape') hideTooltip();
        });
      }
      fragment.append(button);
    }
    $('heatmap').replaceChildren(fragment);
    const yearRecords = records.filter(record => record.date.startsWith(String(year)));
    const yearMs = yearRecords.reduce((sum, record) => sum + record.ms, 0);
    $('year-caption').textContent = `${year} 年 · ${yearRecords.filter(record => record.ms > 0).length} 个听歌日 · ${duration(yearMs)}`;
    updateDayDetail();
  }

  function updateDayDetail() {
    if (!selectedDay) { $('day-detail').textContent = '点击某一天，查看当天的收听分布'; return; }
    const record = recordAt(selectedDay);
    $('day-detail').innerHTML = `<strong>${dateLabel(selectedDay)}</strong> · 听歌 ${duration(record.ms)}`;
  }

  function selectDay(key) {
    selectedDay = key;
    range = 'selected';
    const selectedOption = $('range-select').querySelector('[value="selected"]');
    selectedOption.hidden = false;
    selectedOption.textContent = dateLabel(key);
    $('range-select').value = range;
    $('granularity-select').value = 'hour';
    hideTooltip();
    renderHeatmap();
    renderChart();
    $('heatmap').querySelector(`[data-date="${key}"]`)?.focus({ preventScroll: true });
    hideTooltip();
  }

  function getRange() {
    if (range === 'today') return [TODAY, TODAY];
    if (range === 'selected') return [selectedDay || TODAY, selectedDay || TODAY];
    if (range === 'month') return [`${TODAY.slice(0, 7)}-01`, TODAY];
    if (range === 'custom') return [customStart, customEnd];
    return [addDays(TODAY, -(Number(range) - 1)), TODAY];
  }

  function aggregatePoints(start, end, granularity) {
    if (granularity === 'hour') {
      const record = recordAt(start);
      return record.hourlyMs.map((ms, h) => ({ label: `${String(h).padStart(2, '0')}:00`, fullLabel: `${dateLabel(start)} ${String(h).padStart(2, '0')}:00 — ${String(h + 1).padStart(2, '0')}:00`, ms }));
    }
    const points = [];
    for (let key = start; key <= end; key = addDays(key, granularity === 'week' ? 7 : 1)) {
      const until = granularity === 'week' && addDays(key, 6) < end ? addDays(key, 6) : granularity === 'week' ? end : key;
      let ms = 0;
      for (let date = key; date <= until; date = addDays(date, 1)) ms += recordAt(date).ms;
      points.push({ label: shortDate(key), fullLabel: key === until ? dateLabel(key) : `${dateLabel(key)} — ${dateLabel(until)}`, ms });
    }
    return points;
  }

  function niceCeiling(value) {
    if (value <= 0) return 4;
    const magnitude = 10 ** Math.floor(Math.log10(value));
    const fraction = value / magnitude;
    return (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 4 ? 4 : fraction <= 5 ? 5 : 10) * magnitude;
  }

  function renderChart() {
    if ($('overview-page').hidden) return;
    const [start, end] = getRange();
    const singleDay = start === end;
    const granularitySelect = $('granularity-select');
    granularitySelect.querySelector('[value="hour"]').disabled = !singleDay;
    granularitySelect.querySelector('[value="week"]').disabled = singleDay;
    if (!singleDay && granularitySelect.value === 'hour') granularitySelect.value = 'day';
    if (singleDay && granularitySelect.value === 'week') granularitySelect.value = 'hour';
    chartPoints = aggregatePoints(start, end, granularitySelect.value);
    const maxMs = Math.max(0, ...chartPoints.map(point => point.ms));
    const unitMs = maxMs >= 7_200_000 ? 3_600_000 : 60_000;
    const unitName = unitMs === 3_600_000 ? '小时' : '分钟';
    const values = chartPoints.map(point => point.ms / unitMs);
    const max = niceCeiling(Math.max(...values) * 1.1);
    const svg = $('trend-chart');
    const width = Math.max(280, svg.clientWidth), height = svg.clientHeight || 258;
    const left = 37, right = width - 10, top = 10, bottom = height - 30;
    chartBounds = { left, right, top, bottom, width, height };
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    svg.setAttribute('aria-label', `${dateLabel(start)}至${dateLabel(end)}听歌时长趋势图，下方可展开数据表`);
    const xAt = index => left + (chartPoints.length === 1 ? (right - left) / 2 : index * (right - left) / (chartPoints.length - 1));
    const yAt = value => bottom - value / max * (bottom - top);
    let markup = '';
    for (let tick = 0; tick <= 4; tick++) {
      const value = max * tick / 4;
      const y = yAt(value);
      markup += `<line class="chart-grid" x1="${left}" x2="${right}" y1="${y}" y2="${y}"/><text class="chart-tick" x="${left - 12}" y="${y + 4}" text-anchor="end">${Number(value.toFixed(1))}</text>`;
    }
    const pointPath = values.map((value, index) => `${index ? 'L' : 'M'}${xAt(index).toFixed(2)},${yAt(value).toFixed(2)}`).join(' ');
    if (values.length > 1) markup += `<path class="chart-area" d="${pointPath} L${xAt(values.length - 1)},${bottom} L${xAt(0)},${bottom} Z"/>`;
    markup += `<path class="chart-line" d="${pointPath}"/>`;
    if (values.length === 1) markup += `<circle class="chart-point" cx="${xAt(0)}" cy="${yAt(values[0])}" r="3"/>`;
    const labelCount = Math.min(width < 500 ? 4 : 7, chartPoints.length);
    for (let label = 0; label < labelCount; label++) {
      const index = labelCount === 1 ? 0 : Math.round(label * (chartPoints.length - 1) / (labelCount - 1));
      markup += `<text class="chart-tick" x="${xAt(index)}" y="${height - 8}" text-anchor="${label === 0 ? 'start' : label === labelCount - 1 ? 'end' : 'middle'}">${chartPoints[index].label}</text>`;
    }
    markup += '<g id="chart-hover" visibility="hidden"><line class="chart-crosshair" id="chart-crosshair"/><circle class="chart-point" id="chart-point" r="4"/></g>';
    svg.innerHTML = markup;
    chartPoints.forEach((point, index) => { point.x = xAt(index); point.y = yAt(values[index]); });
    $('chart-unit').textContent = `听歌时长（${unitName}）`;
    const totalMs = chartPoints.reduce((sum, point) => sum + point.ms, 0);
    $('trend-caption').textContent = `这段时间听了 ${duration(totalMs)}`;
    $('range-caption').textContent = start === end ? `${start.replaceAll('-', '.')} · 按${granularitySelect.value === 'hour' ? '小时' : '日'}统计` : `${start.replaceAll('-', '.')} — ${end.replaceAll('-', '.')}`;
    $('chart-empty').hidden = chartPoints.some(point => point.ms > 0);
    $('chart-table').innerHTML = chartPoints.map(point => `<tr><td>${point.fullLabel}</td><td>${duration(point.ms)}</td></tr>`).join('');
  }

  function positionTooltip(rect) {
    const tooltip = $('tooltip');
    tooltip.hidden = false;
    const bounds = tooltip.getBoundingClientRect();
    const left = Math.max(10, Math.min(innerWidth - bounds.width - 10, rect.left + rect.width / 2 - bounds.width / 2));
    let top = rect.top - bounds.height - 12;
    if (top < 10) top = rect.bottom + 12;
    top = Math.max(10, Math.min(top, innerHeight - bounds.height - 10));
    tooltip.style.left = `${left}px`;
    tooltip.style.top = `${top}px`;
  }

  function tooltipContent(label, ms) {
    $('tooltip').innerHTML = `<div class="tooltip-title">${label}</div><div class="tooltip-row"><span>听歌时长</span><span>${duration(ms)}</span></div>`;
  }

  function showRecordTooltip(key, rect) {
    const record = recordAt(key);
    const weekday = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][parseDate(key).getDay()];
    tooltipContent(`${dateLabel(key)} ${weekday}`, record.ms);
    positionTooltip(rect);
  }

  function hideTooltip() { $('tooltip').hidden = true; }

  function showChartPoint(event) {
    if (!chartBounds || !chartPoints.length || !$('chart-empty').hidden) return;
    const rect = $('trend-chart').getBoundingClientRect();
    const localX = event.clientX - rect.left;
    const index = chartPoints.length === 1 ? 0 : Math.max(0, Math.min(chartPoints.length - 1, Math.round((localX - chartBounds.left) / (chartBounds.right - chartBounds.left) * (chartPoints.length - 1))));
    const point = chartPoints[index];
    const line = $('chart-crosshair');
    line.setAttribute('x1', point.x); line.setAttribute('x2', point.x);
    line.setAttribute('y1', chartBounds.top); line.setAttribute('y2', chartBounds.bottom);
    $('chart-point').setAttribute('cx', point.x); $('chart-point').setAttribute('cy', point.y);
    $('chart-hover').setAttribute('visibility', 'visible');
    tooltipContent(point.fullLabel, point.ms);
    positionTooltip({ left: rect.left + point.x, top: rect.top + point.y, bottom: rect.top + point.y, width: 0 });
  }

  function renderSongs() {
    const query = $('song-search').value.trim().normalize('NFKC').toLocaleLowerCase('zh-CN');
    const sort = $('song-sort').value;
    const list = tracks.filter(track => `${track.title}\n${track.artist}`.normalize('NFKC').toLocaleLowerCase('zh-CN').includes(query));
    list.sort((a, b) => sort === 'plays' ? b.plays - a.plays || a.title.localeCompare(b.title, 'zh-CN') : a[sort].localeCompare(b[sort], 'zh-CN') || b.plays - a.plays);
    const fragment = document.createDocumentFragment();
    for (const track of list) {
      const row = document.createElement('tr');
      row.dataset.trackId = track.id;
      const song = document.createElement('td');
      const title = document.createElement('span'); title.className = 'song-title'; title.textContent = track.title;
      const artist = document.createElement('span'); artist.className = 'song-artist'; artist.textContent = track.artist || '未知歌手';
      song.append(title, artist);
      const album = document.createElement('td'); album.className = 'song-album'; album.textContent = track.album || '未知专辑';
      const count = document.createElement('td'); count.className = 'play-count'; count.append(document.createTextNode(number.format(track.plays)));
      const unit = document.createElement('span'); unit.textContent = '次'; count.append(unit);
      row.append(song, album, count); fragment.append(row);
    }
    if (!list.length) {
      const row = document.createElement('tr'), cell = document.createElement('td');
      cell.colSpan = 3; cell.className = 'song-empty';
      cell.textContent = query ? '没有找到匹配的歌曲' : '暂无逐首歌曲记录';
      row.append(cell); fragment.append(row);
    }
    $('song-table').replaceChildren(fragment);
  }

  function renderPage() {
    const ranking = location.hash === '#ranking';
    $('overview-page').hidden = ranking;
    $('ranking-page').hidden = !ranking;
    $('page-title').textContent = ranking ? '听歌排行' : '听歌统计';
    $('page-subtitle').textContent = ranking ? '每首歌曲的累计播放次数' : '累计收听与每日记录';
    document.title = `${ranking ? '听歌排行' : '听歌统计'} · SPW`;
    document.querySelectorAll('[data-page-link]').forEach(link => {
      const active = link.dataset.pageLink === (ranking ? 'ranking' : 'overview');
      link.classList.toggle('active', active);
      if (active) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    });
    hideTooltip();
    if (!ranking) renderChart();
  }

  function toast(message) {
    clearTimeout(toastTimer);
    $('toast').textContent = message;
    $('toast').hidden = false;
    toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4200);
  }

  function openDateDialog() {
    $('date-start').value = customStart;
    $('date-end').value = customEnd;
    $('date-start').max = TODAY; $('date-end').max = TODAY;
    $('date-error').textContent = '';
    $('date-dialog').showModal();
  }

  function validDate(value) {
    return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(parseDate(value).valueOf()) && dateKey(parseDate(value)) === value && value >= '2000-01-01' && value <= TODAY;
  }

  function validateImport(data) {
    if (!data || data.format !== FORMAT || ![1, 2].includes(data.version) || !Array.isArray(data.records)) throw new Error('请选择本预览导出的 JSON 文件');
    if (data.records.length > 10_000) throw new Error('文件包含的记录过多');
    const seen = new Set();
    const validNumber = value => Number.isSafeInteger(value) && value >= 0;
    for (const record of data.records) {
      if (!record || !validDate(record.date) || seen.has(record.date) || !validNumber(record.ms) || record.ms > 86_400_000) throw new Error('日期或听歌时长不合法，未导入');
      seen.add(record.date);
      if (!Array.isArray(record.hourlyMs) || record.hourlyMs.length !== 24 || !record.hourlyMs.every(validNumber) || record.hourlyMs.reduce((a, b) => a + b, 0) !== record.ms) throw new Error('小时数据与每日汇总不一致，未导入');
    }
    // Legacy daily totals cannot reconstruct per-song counts. Preserve only their listening time.
    const importedTracks = data.version === 1 ? [] : data.tracks;
    if (!Array.isArray(importedTracks) || importedTracks.length > 10_000) throw new Error('逐首歌曲数据不合法，未导入');
    const trackIds = new Set();
    for (const track of importedTracks) {
      if (!track || typeof track.id !== 'string' || !track.id.trim() || track.id.length > 128 || trackIds.has(track.id) || typeof track.title !== 'string' || !track.title.trim() || track.title.length > 512 || typeof track.artist !== 'string' || track.artist.length > 512 || typeof track.album !== 'string' || track.album.length > 512 || !validNumber(track.plays) || track.plays > 1_000_000_000) throw new Error('歌曲信息或逐首播放次数不合法，未导入');
      trackIds.add(track.id);
    }
    return {
      records: data.records.map(({ date, ms, hourlyMs }) => ({ date, ms, hourlyMs: [...hourlyMs] })).sort((a, b) => a.date.localeCompare(b.date)),
      tracks: importedTracks.map(({ id, title, artist, album, plays }) => ({ id, title, artist, album, plays })),
      legacy: data.version === 1
    };
  }

  $('song-search').addEventListener('input', renderSongs);
  $('song-sort').addEventListener('change', renderSongs);
  addEventListener('hashchange', () => { renderPage(); window.scrollTo(0, 0); });
  $('year-select').addEventListener('change', () => { hideTooltip(); renderHeatmap(); });
  $('range-select').addEventListener('change', event => {
    if (event.target.value === 'custom') { event.target.value = range; openDateDialog(); return; }
    range = event.target.value;
    if (range === 'today' || range === 'selected') $('granularity-select').value = 'hour';
    hideTooltip(); renderChart();
  });
  $('granularity-select').addEventListener('change', () => { hideTooltip(); renderChart(); });
  $('theme-toggle').addEventListener('click', () => {
    theme = theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = theme;
    safeStorage.set('spw-preview-theme', theme);
    $('theme-toggle').setAttribute('aria-label', `切换到${theme === 'dark' ? '浅色' : '深色'}主题`);
  });
  $('trend-chart').addEventListener('pointermove', showChartPoint);
  $('trend-chart').addEventListener('pointerleave', () => { hideTooltip(); $('chart-hover')?.setAttribute('visibility', 'hidden'); });
  addEventListener('scroll', hideTooltip, { passive: true, capture: true });
  $('date-cancel').addEventListener('click', () => $('date-dialog').close());
  $('date-form').addEventListener('submit', event => {
    event.preventDefault();
    const start = $('date-start').value, end = $('date-end').value;
    if (!validDate(start) || !validDate(end) || start > end) { $('date-error').textContent = '请选择有效日期，结束日期不能早于开始日期。'; return; }
    if ((parseDate(end) - parseDate(start)) / 86_400_000 > 730) { $('date-error').textContent = '一次最多查看两年的趋势。'; return; }
    customStart = start; customEnd = end; range = 'custom';
    $('range-select').value = range;
    if (start === end) $('granularity-select').value = 'hour';
    $('date-dialog').close(); renderChart();
  });
  $('export-button').addEventListener('click', () => {
    const blob = new Blob([JSON.stringify({ format: FORMAT, version: 2, demonstrationDate: TODAY, note: '界面预览数据，不是 SPW 播放历史或正式模组备份格式。播放次数仅按歌曲 ID 独立记录。', records, tracks }, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a'); link.href = url; link.download = `spw-listening-preview-${TODAY}.json`;
    document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    toast('已导出预览数据');
  });
  $('import-button').addEventListener('click', () => $('import-file').click());
  $('import-file').addEventListener('change', async event => {
    const file = event.target.files[0];
    if (!file) return;
    try {
      if (file.size > 2 * 1024 * 1024) throw new Error('预览文件不能超过 2 MB');
      const imported = validateImport(JSON.parse(await file.text()));
      records = imported.records; tracks = imported.tracks;
      byDate = new Map(records.map(record => [record.date, record])); selectedDay = null;
      $('song-search').value = '';
      if (range === 'selected') { range = '30'; $('range-select').value = range; }
      $('range-select').querySelector('[value="selected"]').hidden = true;
      setupYears(); renderSummary(); renderHeatmap(); renderChart(); renderSongs();
      toast(imported.legacy ? '已导入收听时长；旧版文件没有逐首歌曲次数' : '已载入预览数据；未写入 SPW 播放器');
    } catch (error) { toast(error instanceof SyntaxError ? '无法读取 JSON 文件，未导入' : error.message); }
    finally { event.target.value = ''; }
  });

  document.documentElement.dataset.theme = theme;
  setupYears(); renderSummary(); renderHeatmap(); renderSongs(); renderPage();
  new ResizeObserver(() => { hideTooltip(); renderChart(); }).observe($('chart-wrapper'));
})();

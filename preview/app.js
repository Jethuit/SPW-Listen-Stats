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
  const systemTheme = matchMedia('(prefers-color-scheme: dark)');
  const savedTheme = safeStorage.get('spw-preview-theme');
  const themeModes = ['system', 'light', 'dark'];
  const themeLabels = { system: '跟随系统', light: '浅色', dark: '深色' };
  let themeMode = themeModes.includes(savedTheme) ? savedTheme : 'system';
  const chartTypes = { bar: '柱状图', line: '折线图', curve: '曲线图' };
  const savedChartType = safeStorage.get('spw-preview-chart-type');
  let chartType = Object.hasOwn(chartTypes, savedChartType) ? savedChartType : 'line';
  let selectedDay = null;
  let selectedWeek = null;
  let heatmapMode = 'day';
  let range = '30';
  let customStart = addDays(TODAY, -29);
  let customEnd = TODAY;
  let chartPoints = [];
  let chartBounds = null;
  const SONGS_PER_PAGE = 50;
  let songPage = 1;
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
  // Local artwork belongs to the fictional demo albums, not to track IDs.
  const demoCovers = new Map([
    ['林间电台', '日落以后', 'after-sunset'],
    ['远山乐队', '沿途', 'along-the-way'],
    ['夏予', '城市慢拍', 'city-rain'],
    ['白昼航线', '夜行', 'night-drive'],
    ['海岸线', '潮汐之间', 'tides'],
    ['白昼航线', '日间漫步', 'daylight'],
    ['海岸线', '海边现场', 'seaside-live'],
    ['木叶', '散步集', 'woodland']
  ].map(([artist, album, file]) => [JSON.stringify([artist, album]), `assets/covers/${file}.svg`]));
  const defaultCover = 'assets/covers/default.svg';
  const albumCoverFor = track => demoCovers.get(JSON.stringify([track.artist, track.album])) || defaultCover;
  let byDate = new Map(records.map(record => [record.date, record]));
  const emptyRecord = date => ({ date, ms: 0, hourlyMs: Array(24).fill(0) });
  const recordAt = date => byDate.get(date) || emptyRecord(date);

  function duration(ms, compact = false) {
    const minutes = Math.floor(ms / 60_000);
    const hours = Math.floor(minutes / 60);
    if (hours) return compact ? `${number.format(hours)}小时${minutes % 60 ? `${minutes % 60}分` : ''}` : `${number.format(hours)} 小时${minutes % 60 ? ` ${minutes % 60} 分钟` : ''}`;
    return minutes ? `${minutes}${compact ? '分钟' : ' 分钟'}` : ms > 0 ? '不足 1 分钟' : '0 分钟';
  }

  function durationMarkup(ms) {
    const minutes = Math.floor(ms / 60_000);
    const hours = Math.floor(minutes / 60);
    if (hours) return `${number.format(hours)}<small>小时</small>${minutes % 60 ? `${minutes % 60}<small>分钟</small>` : ''}`;
    return minutes ? `${minutes}<small>分钟</small>` : ms > 0 ? '<small>不足</small>1<small>分钟</small>' : '0<small>分钟</small>';
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
    $('peak-day').innerHTML = peak?.ms > 0 ? durationMarkup(peak.ms) : '暂无记录';
    $('peak-day').setAttribute('aria-label', peak?.ms > 0 ? duration(peak.ms) : '暂无记录');
    $('peak-date').textContent = peak?.ms > 0 ? `${peak.date.slice(0, 4)}年${dateLabel(peak.date)}` : '暂无记录';
    $('daily-average').innerHTML = active.length ? durationMarkup(totalMs / active.length) : '暂无记录';
    $('daily-average').setAttribute('aria-label', active.length ? duration(totalMs / active.length) : '暂无记录');
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

  function heatmapWeeks(year) {
    const first = `${year}-01-01`, last = `${year}-12-31`;
    const monday = addDays(first, -((parseDate(first).getDay() + 6) % 7));
    const weeks = [];
    for (let start = monday; start <= last; start = addDays(start, 7)) {
      const end = addDays(start, 6);
      const from = start < first ? first : start;
      const until = [end, last, TODAY].sort()[0];
      let ms = 0;
      for (let key = from; key <= until; key = addDays(key, 1)) ms += recordAt(key).ms;
      weeks.push({ start, end, from, until, ms, future: from > TODAY });
    }
    return weeks;
  }

  function setHeatmapMode(mode) {
    if (!['day', 'week'].includes(mode)) return;
    heatmapMode = mode;
    hideTooltip();
    renderHeatmap();
  }

  function renderWeeklyCells(year) {
    const weeks = heatmapWeeks(year);
    const peakMs = Math.max(0, ...weeks.map(week => week.ms));
    const current = weeks.find(week => week.start <= TODAY && week.end >= TODAY);
    const focusWeek = weeks.find(week => week.start === selectedWeek)?.start || current?.start || weeks.filter(week => !week.future).at(-1)?.start;
    const fragment = document.createDocumentFragment();
    for (const week of weeks) {
      const button = document.createElement('button');
      button.className = 'week-cell';
      button.dataset.week = week.start;
      button.tabIndex = week.start === focusWeek ? 0 : -1;
      button.disabled = week.future;
      button.classList.toggle('future', week.future);
      button.classList.toggle('selected', week.start === selectedWeek);
      button.setAttribute('aria-pressed', String(week.start === selectedWeek));
      const coverage = `${week.from.replaceAll('-', '/')} - ${week.until.replaceAll('-', '/')}`;
      button.setAttribute('aria-label', week.future ? `${week.start} 起的一周，未来日期` : `${coverage}，听歌时长 ${duration(week.ms)}`);
      const filled = peakMs > 0 && week.ms > 0 ? Math.max(1, Math.ceil(week.ms / peakMs * 7)) : 0;
      for (let row = 0; row < 7; row++) {
        const block = document.createElement('span');
        block.className = `week-block${row >= 7 - filled ? ' filled' : ''}`;
        block.setAttribute('aria-hidden', 'true');
        button.append(block);
      }
      const show = () => {
        if (week.future) return;
        tooltipContent(coverage, week.ms);
        positionTooltip(button.getBoundingClientRect());
      };
      button.addEventListener('mouseenter', show);
      button.addEventListener('focus', show);
      button.addEventListener('mouseleave', hideTooltip);
      button.addEventListener('blur', hideTooltip);
      button.addEventListener('click', () => {
        selectedWeek = week.start;
        customStart = week.from; customEnd = week.until; range = 'custom';
        $('range-select').value = range;
        $('granularity-select').value = 'day';
        hideTooltip(); renderHeatmap(); renderChart();
        $('heatmap').querySelector(`[data-week="${week.start}"]`)?.focus({ preventScroll: true });
        hideTooltip();
      });
      button.addEventListener('keydown', event => {
        if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
          event.preventDefault();
          const next = $('heatmap').querySelector(`[data-week="${addDays(week.start, event.key === 'ArrowLeft' ? -7 : 7)}"]:not(:disabled)`);
          if (next) { button.tabIndex = -1; next.tabIndex = 0; next.focus(); }
        }
        if (event.key === 'Escape') hideTooltip();
      });
      fragment.append(button);
    }
    $('heatmap').replaceChildren(fragment);
  }

  function renderHeatmap() {
    const year = Number($('year-select').value);
    const first = `${year}-01-01`, last = `${year}-12-31`;
    const start = addDays(first, -((parseDate(first).getDay() + 6) % 7));
    const end = addDays(last, 6 - ((parseDate(last).getDay() + 6) % 7));
    const dates = [];
    for (let key = start; key <= end; key = addDays(key, 1)) dates.push(key);
    const weeks = dates.length / 7;
    const weekly = heatmapMode === 'week';
    $('heatmap').classList.toggle('is-weekly', weekly);
    $('heatmap').setAttribute('aria-label', weekly ? '按周显示的年度听歌时长，每列代表周一至周日的一周' : '按日显示的年度听歌热力图');
    $('weekday-labels').hidden = weekly;
    $('heatmap-legend').hidden = weekly;
    $('weekly-scale-note').hidden = !weekly;
    document.querySelectorAll('[data-heatmap-mode]').forEach(button => {
      const active = button.dataset.heatmapMode === heatmapMode;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', String(active));
    });
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
    for (const key of weekly ? [] : dates) {
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
    if (weekly) renderWeeklyCells(year);
    else $('heatmap').replaceChildren(fragment);
    const yearRecords = records.filter(record => record.date.startsWith(String(year)));
    const yearMs = yearRecords.reduce((sum, record) => sum + record.ms, 0);
    $('year-caption').textContent = `${year} 年 · ${yearRecords.filter(record => record.ms > 0).length} 个听歌日 · ${duration(yearMs)}`;
    updateDayDetail();
  }

  function updateDayDetail() {
    if (heatmapMode === 'week') {
      const week = heatmapWeeks(Number($('year-select').value)).find(item => item.start === selectedWeek);
      $('day-detail').textContent = week ? `${dateLabel(week.from)} — ${dateLabel(week.until)} · 听歌 ${duration(week.ms)}` : '周一至周日为一周；点击某一周，查看该周的每日分布';
      return;
    }
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
    const multiYear = start.slice(0, 4) !== end.slice(0, 4);
    const fullDate = key => `${multiYear ? `${key.slice(0, 4)}年` : ''}${dateLabel(key)}`;
    const points = [];
    for (let key = start; key <= end; key = addDays(key, granularity === 'week' ? 7 : 1)) {
      const until = granularity === 'week' && addDays(key, 6) < end ? addDays(key, 6) : granularity === 'week' ? end : key;
      let ms = 0;
      for (let date = key; date <= until; date = addDays(date, 1)) ms += recordAt(date).ms;
      points.push({ label: multiYear ? key.replaceAll('-', '/') : shortDate(key), fullLabel: key === until ? fullDate(key) : `${fullDate(key)} — ${fullDate(until)}`, ms });
    }
    return points;
  }

  function niceCeiling(value) {
    if (value <= 0) return 4;
    const magnitude = 10 ** Math.floor(Math.log10(value));
    const fraction = value / magnitude;
    return (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 4 ? 4 : fraction <= 5 ? 5 : 10) * magnitude;
  }

  function axisNumber(value, step) {
    const digits = Math.max(0, Math.min(8, Math.ceil(-Math.log10(step)) + 1));
    return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: digits }).format(Number(value.toFixed(digits)));
  }

  function chartTickIndices(points, xAt, minGap) {
    if (points.length <= 1) return [0];
    const last = points.length - 1;
    const count = Math.max(2, Math.min(7, Math.floor((xAt(last) - xAt(0)) / minGap) + 1));
    const stride = Math.ceil(last / (count - 1));
    const indices = [0];
    for (let index = stride; index < last; index += stride) indices.push(index);
    while (indices.length > 1 && xAt(last) - xAt(indices.at(-1)) < minGap) indices.pop();
    indices.push(last);
    return indices;
  }

  // Monotone cubic interpolation: each segment stays between its two measured endpoints.
  function chartPath(points, smooth = false) {
    if (!points.length) return '';
    const coordinate = value => Number(value.toFixed(3));
    let path = `M${coordinate(points[0].x)},${coordinate(points[0].y)}`;
    if (!smooth || points.length < 2) {
      return path + points.slice(1).map(point => ` L${coordinate(point.x)},${coordinate(point.y)}`).join('');
    }
    const intervals = points.slice(1).map((point, index) => point.x - points[index].x);
    const slopes = points.slice(1).map((point, index) => (point.y - points[index].y) / intervals[index]);
    const tangents = [slopes[0]];
    for (let i = 1; i < points.length - 1; i++) {
      if (slopes[i - 1] * slopes[i] <= 0) tangents.push(0);
      else {
        const w1 = 2 * intervals[i] + intervals[i - 1];
        const w2 = intervals[i] + 2 * intervals[i - 1];
        tangents.push((w1 + w2) / (w1 / slopes[i - 1] + w2 / slopes[i]));
      }
    }
    tangents.push(slopes[slopes.length - 1]);
    for (let i = 0; i < slopes.length; i++) {
      if (slopes[i] === 0) { tangents[i] = 0; tangents[i + 1] = 0; continue; }
      const a = tangents[i] / slopes[i], b = tangents[i + 1] / slopes[i];
      const magnitude = Math.hypot(a, b);
      if (magnitude > 3) {
        tangents[i] *= 3 / magnitude;
        tangents[i + 1] *= 3 / magnitude;
      }
    }
    for (let i = 0; i < points.length - 1; i++) {
      const step = intervals[i] / 3;
      const from = points[i], to = points[i + 1];
      path += ` C${coordinate(from.x + step)},${coordinate(from.y + tangents[i] * step)} ${coordinate(to.x - step)},${coordinate(to.y - tangents[i + 1] * step)} ${coordinate(to.x)},${coordinate(to.y)}`;
    }
    return path;
  }

  function syncChartTypeButtons() {
    document.querySelectorAll('[data-chart-type]').forEach(button => {
      const active = button.dataset.chartType === chartType;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', String(active));
    });
  }

  function applyTheme() {
    const resolvedTheme = themeMode === 'system' ? (systemTheme.matches ? 'dark' : 'light') : themeMode;
    document.documentElement.dataset.theme = resolvedTheme;
    document.documentElement.dataset.themeMode = themeMode;
    const nextMode = themeModes[(themeModes.indexOf(themeMode) + 1) % themeModes.length];
    const current = themeMode === 'system' ? `跟随系统（当前${themeLabels[resolvedTheme]}）` : themeLabels[themeMode];
    $('theme-toggle').dataset.themeMode = themeMode;
    $('theme-toggle').setAttribute('aria-label', `当前${current}，点击切换为${themeLabels[nextMode]}`);
    $('theme-toggle').title = `${current} · 点击切换为${themeLabels[nextMode]}`;
    document.querySelectorAll('[data-theme-icon]').forEach(icon => {
      icon.toggleAttribute('hidden', icon.dataset.themeIcon !== themeMode);
    });
  }

  function setThemeMode(mode) {
    if (!themeModes.includes(mode)) return;
    themeMode = mode;
    safeStorage.set('spw-preview-theme', mode);
    applyTheme();
  }

  function handleSystemThemeChange() {
    if (themeMode === 'system') applyTheme();
  }

  function cycleTheme() {
    setThemeMode(themeModes[(themeModes.indexOf(themeMode) + 1) % themeModes.length]);
  }

  function setChartType(type) {
    if (!Object.hasOwn(chartTypes, type)) return;
    chartType = type;
    safeStorage.set('spw-preview-chart-type', type);
    syncChartTypeButtons();
    hideTooltip(); renderChart();
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
    const hourly = granularitySelect.value === 'hour';
    chartPoints = aggregatePoints(start, end, granularitySelect.value);
    const totalMs = chartPoints.reduce((sum, point) => sum + point.ms, 0);
    const averageMs = chartPoints.length ? totalMs / chartPoints.length : 0;
    const averageName = { day: '日均', week: '周均', hour: '小时均值' }[granularitySelect.value];
    const averageLabel = `${averageName} ${duration(averageMs)}`;
    const maxMs = Math.max(0, ...chartPoints.map(point => point.ms));
    const showAverage = !hourly && maxMs > 0;
    const unitMs = maxMs >= 7_200_000 ? 3_600_000 : 60_000;
    const unitName = unitMs === 3_600_000 ? '小时' : '分钟';
    const values = chartPoints.map(point => point.ms / unitMs);
    const max = niceCeiling(Math.max(...values) * 1.1);
    const svg = $('trend-chart');
    const width = Math.max(280, svg.clientWidth), height = svg.clientHeight || 258;
    const measure = document.createElement('canvas').getContext('2d');
    if (measure) measure.font = `11px ${getComputedStyle(svg).fontFamily}`;
    const labelWidth = Math.max(...chartPoints.map(point => measure ? measure.measureText(point.label).width : point.label.length * 7));
    // Reserve space for centered endpoint labels instead of shifting the labels away from their data.
    const left = Math.max(52, labelWidth / 2 + 6), right = width - Math.max(10, labelWidth / 2 + 6), top = 10, bottom = height - 30;
    const banded = hourly || chartType === 'bar';
    chartBounds = { left, right, top, bottom, width, height, banded };
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    svg.dataset.chartType = chartType;
    svg.setAttribute('aria-label', `${dateLabel(start)}至${dateLabel(end)}听歌时长${chartTypes[chartType]}${showAverage ? `，${averageLabel}，包含零收听时段` : ''}${hourly ? '，时间标签表示小时区间起点，最后一段为23:00至24:00' : ''}`);
    const slotWidth = (right - left) / chartPoints.length;
    // Hourly values are interval totals: place them at the middle of each hour.
    const xAt = index => banded ? left + (index + 0.5) * slotWidth : left + (chartPoints.length === 1 ? (right - left) / 2 : index * (right - left) / (chartPoints.length - 1));
    const yAt = value => bottom - value / max * (bottom - top);
    const averageY = yAt(averageMs / unitMs);
    const averageValue = averageMs / unitMs;
    const averageAxisLabel = averageValue > 0 && averageValue < 0.01 ? '<0.01' : number.format(Number(averageValue.toFixed(2)));
    let markup = '';
    for (let tick = 0; tick <= 4; tick++) {
      const value = max * tick / 4;
      const y = yAt(value);
      markup += `<line class="chart-grid" x1="${left}" x2="${right}" y1="${y}" y2="${y}"/>`;
      if (!showAverage || Math.abs(y - averageY) >= 14) {
        markup += `<text class="chart-tick" data-axis-value="${value}" x="${left - 12}" y="${y + 4}" text-anchor="end">${axisNumber(value, max / 4)}</text>`;
      }
    }
    if (chartType === 'bar') {
      const barWidth = Math.min(32, slotWidth * 0.65);
      values.forEach((value, index) => {
        if (value <= 0) return;
        const y = yAt(value), barHeight = bottom - y;
        markup += `<rect class="chart-bar" data-point-index="${index}" x="${xAt(index) - barWidth / 2}" y="${y}" width="${barWidth}" height="${barHeight}" rx="${Math.min(3, barWidth / 3, barHeight / 2)}"/>`;
      });
    } else {
      const pointPath = chartPath(values.map((value, index) => ({ x: xAt(index), y: yAt(value) })), chartType === 'curve');
      if (values.length > 1) markup += `<path class="chart-area" d="${pointPath} L${xAt(values.length - 1)},${bottom} L${xAt(0)},${bottom} Z"/>`;
      markup += `<path class="chart-line" d="${pointPath}"/>`;
      if (values.length === 1) markup += `<circle class="chart-point" cx="${xAt(0)}" cy="${yAt(values[0])}" r="3"/>`;
    }
    if (showAverage) {
      markup += `<g class="chart-average"><title>${averageLabel}，单位：${unitName}</title><line class="chart-average-line" x1="${left - 6}" x2="${right}" y1="${averageY}" y2="${averageY}"/><text class="chart-average-label" x="${left - 12}" y="${averageY + 4}" text-anchor="end">${averageAxisLabel.replace('<', '&lt;')}</text></g>`;
    }
    for (const index of chartTickIndices(chartPoints, xAt, labelWidth + 16)) {
      const x = xAt(index);
      markup += `<line class="chart-axis-tick" x1="${x}" x2="${x}" y1="${bottom}" y2="${bottom + 4}"/><text class="chart-tick" data-tick-index="${index}" x="${x}" y="${height - 8}" text-anchor="middle">${chartPoints[index].label}</text>`;
    }
    markup += '<g id="chart-hover" visibility="hidden"><line class="chart-crosshair" id="chart-crosshair"/><circle class="chart-point" id="chart-point" r="4"/></g>';
    svg.innerHTML = markup;
    chartPoints.forEach((point, index) => { point.x = xAt(index); point.y = yAt(values[index]); });
    $('chart-unit').textContent = `听歌时长（${unitName}）`;
    $('trend-caption').textContent = `这段时间听了 ${duration(totalMs)}`;
    $('chart-empty').hidden = chartPoints.some(point => point.ms > 0);
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
    const time = duration(ms).replaceAll(' ', '');
    $('tooltip').innerHTML = `<div class="tooltip-title">${label}</div><div class="tooltip-row"><span class="tooltip-label">听歌时长</span><span class="tooltip-value">${time}</span></div>`;
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
    if (rect.width <= 0 || rect.height <= 0) return;
    const scaleX = rect.width / chartBounds.width, scaleY = rect.height / chartBounds.height;
    const localX = (event.clientX - rect.left) / scaleX;
    const nearest = chartBounds.banded ? Math.floor((localX - chartBounds.left) / (chartBounds.right - chartBounds.left) * chartPoints.length) : chartPoints.length === 1 ? 0 : Math.round((localX - chartPoints[0].x) / (chartPoints[chartPoints.length - 1].x - chartPoints[0].x) * (chartPoints.length - 1));
    const index = Math.max(0, Math.min(chartPoints.length - 1, nearest));
    const point = chartPoints[index];
    $('trend-chart').querySelector('.chart-bar.is-active')?.classList.remove('is-active');
    $('trend-chart').querySelector(`[data-point-index="${index}"]`)?.classList.add('is-active');
    const line = $('chart-crosshair');
    line.setAttribute('x1', point.x); line.setAttribute('x2', point.x);
    line.setAttribute('y1', chartBounds.top); line.setAttribute('y2', chartBounds.bottom);
    $('chart-point').setAttribute('cx', point.x); $('chart-point').setAttribute('cy', point.y);
    $('chart-hover').setAttribute('visibility', 'visible');
    tooltipContent(point.fullLabel, point.ms);
    positionTooltip({ left: rect.left + point.x * scaleX, top: rect.top + point.y * scaleY, bottom: rect.top + point.y * scaleY, width: 0 });
  }

  function renderSongs() {
    const query = $('song-search').value.trim().normalize('NFKC').toLocaleLowerCase('zh-CN');
    const rankedTracks = [...tracks].sort((a, b) => b.plays - a.plays || a.title.localeCompare(b.title, 'zh-CN'));
    const ranks = new Map(rankedTracks.map((track, index) => [track.id, index + 1]));
    const list = rankedTracks.filter(track => `${track.title}\n${track.artist}`.normalize('NFKC').toLocaleLowerCase('zh-CN').includes(query));
    const pageCount = Math.max(1, Math.ceil(list.length / SONGS_PER_PAGE));
    songPage = Math.max(1, Math.min(songPage, pageCount));
    const start = (songPage - 1) * SONGS_PER_PAGE;
    const pageTracks = list.slice(start, start + SONGS_PER_PAGE);
    const fragment = document.createDocumentFragment();
    for (const track of pageTracks) {
      const row = document.createElement('tr');
      row.dataset.trackId = track.id;
      const rank = document.createElement('td'); rank.className = 'song-rank'; rank.textContent = String(ranks.get(track.id));
      const song = document.createElement('td');
      const entry = document.createElement('div'); entry.className = 'song-entry';
      const cover = document.createElement('img');
      cover.className = 'album-cover'; cover.src = albumCoverFor(track);
      cover.alt = `${track.album || '未知专辑'}的专辑封面`;
      cover.width = 44; cover.height = 44; cover.loading = 'lazy'; cover.decoding = 'async';
      cover.addEventListener('error', () => { cover.src = defaultCover; }, { once: true });
      const metadata = document.createElement('div'); metadata.className = 'song-metadata';
      const title = document.createElement('span'); title.className = 'song-title'; title.textContent = track.title;
      const artist = document.createElement('span'); artist.className = 'song-artist'; artist.textContent = track.artist || '未知歌手';
      metadata.append(title, artist); entry.append(cover, metadata); song.append(entry);
      const album = document.createElement('td'); album.className = 'song-album'; album.textContent = track.album || '未知专辑';
      const count = document.createElement('td'); count.className = 'play-count'; count.append(document.createTextNode(number.format(track.plays)));
      const unit = document.createElement('span'); unit.textContent = '次'; count.append(unit);
      row.append(rank, song, album, count); fragment.append(row);
    }
    if (!list.length) {
      const row = document.createElement('tr'), cell = document.createElement('td');
      cell.colSpan = 4; cell.className = 'song-empty';
      cell.textContent = query ? '没有找到匹配的歌曲' : '暂无逐首歌曲记录';
      row.append(cell); fragment.append(row);
    }
    $('song-table').replaceChildren(fragment);
    const countLabel = query ? `匹配 ${number.format(list.length)} 首 / 共 ${number.format(tracks.length)} 首` : `共 ${number.format(tracks.length)} 首`;
    $('song-page-summary').textContent = `${countLabel}${list.length ? ` · 显示 ${number.format(start + 1)}–${number.format(start + pageTracks.length)} 首` : ''} · 每页 ${SONGS_PER_PAGE} 首`;
    renderSongPagination(pageCount);
  }

  function renderSongPagination(pageCount) {
    document.querySelectorAll('[data-song-pagination]').forEach(nav => {
      nav.hidden = pageCount <= 1;
      const fragment = document.createDocumentFragment();
      const addButton = (label, page, disabled = false, current = false) => {
        const button = document.createElement('button');
        button.type = 'button'; button.textContent = label;
        button.dataset.songPage = String(page); button.disabled = disabled;
        button.setAttribute('aria-label', /^\d+$/.test(label) ? `第 ${page} 页，共 ${pageCount} 页` : label);
        if (current) button.setAttribute('aria-current', 'page');
        fragment.append(button);
      };
      addButton('上一页', songPage - 1, songPage === 1);
      const pages = new Set([1, pageCount]);
      const from = pageCount <= 7 ? 1 : songPage <= 3 ? 2 : Math.max(2, songPage - 1);
      const until = pageCount <= 7 ? pageCount : songPage >= pageCount - 2 ? pageCount - 1 : Math.min(pageCount - 1, Math.max(4, songPage + 1));
      for (let page = from; page <= until; page++) pages.add(page);
      let previous = 0;
      for (const page of [...pages].sort((a, b) => a - b)) {
        if (previous && page - previous > 1) {
          const ellipsis = document.createElement('span'); ellipsis.textContent = '…';
          ellipsis.setAttribute('aria-hidden', 'true'); fragment.append(ellipsis);
        }
        addButton(String(page), page, false, page === songPage);
        previous = page;
      }
      addButton('下一页', songPage + 1, songPage === pageCount);
      nav.replaceChildren(fragment);
    });
  }

  function changeSongPage(page) {
    if (!Number.isInteger(page) || page < 1 || page === songPage) return;
    songPage = page;
    renderSongs();
    $('ranking-page').scrollIntoView({ block: 'start' });
    $('song-pagination-top').querySelector('[aria-current="page"]')?.focus({ preventScroll: true });
  }

  function searchSongs() {
    songPage = 1;
    renderSongs();
  }

  function renderPage() {
    const ranking = location.hash === '#ranking';
    $('overview-page').hidden = ranking;
    $('ranking-page').hidden = !ranking;
    $('page-title').textContent = '听歌统计';
    document.title = '听歌统计 · SPW';
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

  document.querySelectorAll('[data-chart-type]').forEach(button => button.addEventListener('click', () => setChartType(button.dataset.chartType)));
  document.querySelectorAll('[data-heatmap-mode]').forEach(button => button.addEventListener('click', () => setHeatmapMode(button.dataset.heatmapMode)));
  $('song-search').addEventListener('input', searchSongs);
  document.querySelectorAll('[data-song-pagination]').forEach(nav => nav.addEventListener('click', event => {
    const button = event.target.closest('button[data-song-page]');
    if (button && !button.disabled) changeSongPage(Number(button.dataset.songPage));
  }));
  addEventListener('hashchange', () => { renderPage(); window.scrollTo(0, 0); });
  $('year-select').addEventListener('change', () => { hideTooltip(); renderHeatmap(); });
  $('range-select').addEventListener('change', event => {
    if (event.target.value === 'custom') { event.target.value = range; openDateDialog(); return; }
    range = event.target.value;
    if (range === 'today' || range === 'selected') $('granularity-select').value = 'hour';
    hideTooltip(); renderChart();
  });
  $('granularity-select').addEventListener('change', () => { hideTooltip(); renderChart(); });
  $('theme-toggle').addEventListener('click', cycleTheme);
  systemTheme.addEventListener('change', handleSystemThemeChange);
  $('trend-chart').addEventListener('pointermove', showChartPoint);
  $('trend-chart').addEventListener('pointerleave', () => {
    hideTooltip(); $('chart-hover')?.setAttribute('visibility', 'hidden');
    $('trend-chart').querySelector('.chart-bar.is-active')?.classList.remove('is-active');
  });
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
      records = imported.records; tracks = imported.tracks; songPage = 1;
      byDate = new Map(records.map(record => [record.date, record])); selectedDay = null; selectedWeek = null;
      $('song-search').value = '';
      if (range === 'selected') { range = '30'; $('range-select').value = range; }
      $('range-select').querySelector('[value="selected"]').hidden = true;
      setupYears(); renderSummary(); renderHeatmap(); renderChart(); renderSongs();
      toast(imported.legacy ? '已导入收听时长；旧版文件没有逐首歌曲次数' : '已载入预览数据；未写入 SPW 播放器');
    } catch (error) { toast(error instanceof SyntaxError ? '无法读取 JSON 文件，未导入' : error.message); }
    finally { event.target.value = ''; }
  });

  applyTheme();
  syncChartTypeButtons();
  setupYears(); renderSummary(); renderHeatmap(); renderSongs(); renderPage();
  new ResizeObserver(() => { hideTooltip(); renderChart(); }).observe($('chart-wrapper'));
})();

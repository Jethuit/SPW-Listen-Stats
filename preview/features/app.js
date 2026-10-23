'use strict';
const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const tracks = [
  {title:'沿途的光', artist:'林间来信', album:'沿途的光', cover:'along-the-way', all:38, range:12},
  {title:'夜间航行', artist:'蓝色电台', album:'Night Drive', cover:'night-drive', all:31, range:9},
  {title:'潮汐', artist:'远岸', album:'Tides', cover:'tides', all:27, range:8},
  {title:'城市微雨', artist:'林间来信', album:'City Rain', cover:'city-rain', all:22, range:5},
  {title:'日光', artist:'夏日信号', album:'Daylight', cover:'daylight', all:18, range:4}
];
const datasets = {
  week:{values:[74,118,93,156,101,122,206],previous:[92,80,97,124,100,103,104],labels:['10/16','10/17','10/18','10/19','10/20','10/21','10/22'],dates:['2026年10月16日','2026年10月17日','2026年10月18日','2026年10月19日','2026年10月20日','2026年10月21日','2026年10月22日'],range:'2026年10月16日—22日',previousLabel:'10月9日—15日',days:7},
  day:{values:[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,15,30,27,20,9,0],previous:[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,25,40,35,36,20,0],labels:Array.from({length:24},(_,i)=>`${String(i).padStart(2,'0')}:00`),range:'2026年10月20日',previousLabel:'10月19日',days:1}
};
const state={variant:new URLSearchParams(location.search).get('variant')==='b'?'b':'a',page:'overview',range:'week',compare:true,index:4,panel:null,scope:'all',rankMode:'song',query:'',shareDark:false,showTitles:true,showCounts:true};
let opener=null;
const total=(xs)=>xs.reduce((a,b)=>a+b,0);
const duration=(minutes)=>minutes<60?`${minutes}分钟`:`${Math.floor(minutes/60)}小时${minutes%60?`${minutes%60}分钟`:''}`;
const current=()=>datasets[state.range];
const cover=(item)=>`<img class="cover" src="../assets/covers/${item.cover}.svg" alt="">`;
const rangeCount=(item)=>state.range==='day'?Math.max(1,Math.round(item.range/3)):item.range;

function setReviewHeight(){document.documentElement.style.setProperty('--review-height',`${$('.review-bar').offsetHeight}px`);}
function renderHeatmap(){
  $('.months').innerHTML=Array.from({length:12},(_,i)=>`<span>${i+1}月</span>`).join('');
  const start=3,activeStart=284;
  $('#heatmap').innerHTML=Array.from({length:371},(_,i)=>`<i style="${i<start||i>=start+365?'visibility:hidden;':''}${i>=activeStart&&i<activeStart+14?`opacity:${.4+(i%4)*.2}`:''}" class="${i>=activeStart&&i<activeStart+14?'active':''}"></i>`).join('');
}
function renderChart(){
  const d=current(),sum=total(d.values),before=total(d.previous),delta=sum-before;
  $('#range-total').textContent=`${d.range} · 这段时间听了 ${duration(sum)}`;
  $('#comparison').hidden=!state.compare;
  $('#previous-key').hidden=!state.compare;
  $('#comparison-change').textContent=`比上一周期${delta>=0?'多':'少'} ${duration(Math.abs(delta))}（${delta>=0?'+':'−'}${Math.round(Math.abs(delta)/before*100)}%）`;
  $('#comparison-period').textContent=`对比 ${d.previousLabel} · ${duration(before)}`;
  const width=1000,left=45,right=15,top=22,bottom=226,step=(width-left-right)/d.values.length,max=state.range==='day'?60:240;
  const x=i=>left+step*(i+.5),y=v=>bottom-v/max*(bottom-top);
  const grid=Array.from({length:5},(_,i)=>{const v=max*i/4;return `<line class="grid" x1="${left}" x2="${width-right}" y1="${y(v)}" y2="${y(v)}"/><text x="30" y="${y(v)+4}" text-anchor="end">${v}</text>`;}).join('');
  const bars=d.values.map((v,i)=>`<g class="hit" role="button" tabindex="0" data-point="${i}" aria-label="查看${d.labels[i]}歌曲明细，${duration(v)}"><rect x="${left+step*i}" y="${top}" width="${step}" height="${bottom-top}" fill="transparent"/><rect class="bar ${state.panel==='detail'&&state.index===i?'selected':''}" x="${x(i)-step*.25}" y="${y(v)}" width="${step*.5}" height="${Math.max(v?2:0,bottom-y(v))}" rx="3"/>${state.range==='week'||i%4===0||i===23?`<text x="${x(i)}" y="250" text-anchor="middle">${d.labels[i]}</text>`:''}</g>`).join('');
  // Extend the comparison stroke through the outer half-buckets without moving real data points.
  const previousValues=d.previous,last=previousValues.length-1;
  const edgeValue=(value,neighbor)=>Math.max(0,Math.min(max,value+(value-neighbor)/2));
  const previousPoints=[
    [left,y(edgeValue(previousValues[0],previousValues[1]??previousValues[0]))],
    ...previousValues.map((v,i)=>[x(i),y(v)]),
    [width-right,y(edgeValue(previousValues[last],previousValues[last-1]??previousValues[last]))]
  ];
  const previous=state.compare?`<path class="previous-line" d="${previousPoints.map(([px,py],i)=>`${i?'L':'M'}${px},${py}`).join(' ')}"/>`:'';
  const selection=state.panel==='detail'?`<rect class="selection" x="${left+step*state.index}" y="${top}" width="${step}" height="${bottom-top}"/>`:'';
  $('#chart').innerHTML=`<svg viewBox="0 0 1000 265" role="group" aria-label="时长分布，选择柱子查看歌曲明细"><text x="${left}" y="11">时长 / min</text>${grid}${selection}${bars}${previous}</svg>`;
  $$('#chart [data-point]').forEach(el=>{
    el.addEventListener('click',()=>openDetail(Number(el.dataset.point),el));
    el.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();openDetail(Number(el.dataset.point),el);}});
  });
}
function renderRanking(){
  const d=current();
  $('#ranking-title').textContent={song:'歌曲播放次数',artist:'艺术家播放次数',album:'专辑播放次数'}[state.rankMode];
  $('#ranking-caption').textContent=state.scope==='all'?'全部时间的有效播放次数':`${d.range} · 与时长分布使用同一日期范围`;
  $('#edit-range').textContent=`当前范围：${d.range}　更改`;
  $$('[data-scope]').forEach(b=>b.setAttribute('aria-pressed',b.dataset.scope===state.scope));
  let rows=tracks.map(t=>({...t,count:state.scope==='all'?t.all:rangeCount(t)}));
  if(state.rankMode==='artist'){
    const groups=new Map();
    rows.forEach(t=>{const old=groups.get(t.artist);if(old)old.count+=t.count;else groups.set(t.artist,{...t,title:t.artist,artist:'艺术家'});});
    rows=[...groups.values()];
  }else if(state.rankMode==='album') rows=rows.map(t=>({...t,title:t.album}));
  rows.sort((a,b)=>b.count-a.count);
  rows=rows.map((t,i)=>({...t,rank:i+1})).filter(t=>`${t.title} ${t.artist} ${t.album}`.toLowerCase().includes(state.query.toLowerCase()));
  $('#rank-table').innerHTML=`<table class="table"><thead><tr><th>排名</th><th>${{song:'标题',artist:'艺术家',album:'专辑'}[state.rankMode]}</th>${state.rankMode==='song'?'<th class="rank-album">专辑</th>':''}<th>播放次数</th></tr></thead><tbody>${rows.map(t=>`<tr><td>${t.rank}</td><td><div class="track">${cover(t)}<div><strong>${t.title}</strong><small>${t.artist}</small></div></div></td>${state.rankMode==='song'?`<td class="muted rank-album">${t.album}</td>`:''}<td>${t.count} <small class="muted">次</small></td></tr>`).join('')}</tbody></table>${!rows.length?'<p class="empty">没有匹配的结果</p>':''}`;
}
function detailLabel(){return state.range==='week'?current().dates[state.index]:`2026年10月20日 ${String(state.index).padStart(2,'0')}:00—${String(state.index+1).padStart(2,'0')}:00`;}
function detailMarkup(){
  const minutes=current().values[state.index];
  const parts=[Math.floor(minutes*.36),Math.floor(minutes*.27),Math.floor(minutes*.22)];parts.push(minutes-total(parts));
  return `<div class="detail-box"><div class="detail-head"><div><h3>${detailLabel()}</h3><p class="muted">歌曲收听明细</p></div>${state.variant==='a'?'<button class="icon-button inline-close" aria-label="收起歌曲明细">×</button>':''}</div><div class="detail-stats"><span><strong>${duration(minutes)}</strong>收听时长</span><span><strong>${minutes?4:0}</strong>首歌曲</span></div>${minutes?`<table class="table"><thead><tr><th></th><th>歌曲</th><th>本时段收听</th></tr></thead><tbody>${tracks.slice(0,4).map((t,i)=>`<tr><td>${i+1}</td><td><div class="track">${cover(t)}<div><strong>${t.title}</strong><small>${t.artist} ${i===3?'<span class="precision">近似记录</span>':''}</small></div></div></td><td>${duration(parts[i])}</td></tr>`).join('')}</tbody></table><p class="muted footnote">包含近似历史记录，时段归属可能有偏差。</p>`:'<p class="empty">这个时段没有听歌记录</p>'}<div class="detail-foot"><span class="muted">仅展示可归属到该时段的记录</span><button class="text-link go-ranking">查看当前范围排行 →</button></div></div>`;
}
function shareCardMarkup(){
  const d=current(),minutes=total(d.values);
  return `<div class="share-card ${state.shareDark?'card-dark':''}"><p class="share-kicker">我的听歌记录</p><p class="share-date">${d.range}</p><p class="share-total">${Math.floor(minutes/60)}<small>小时</small>${minutes%60}<small>分钟</small></p><p class="share-days">${d.days} 个听歌日 · 每一天，都有音乐</p><div class="share-chart" aria-hidden="true">${d.values.map(v=>`<i style="height:${v/Math.max(...d.values)*100}%"></i>`).join('')}</div>${state.showTitles?`<p class="share-list-title">这段时间最常听 · TOP 5</p>${tracks.map((t,i)=>`<div class="share-row"><span>${i+1}　${t.title} <small>· ${t.artist}</small></span>${state.showCounts?`<span>${rangeCount(t)}次</span>`:''}</div>`).join('')}`:''}<p class="share-brand">听歌统计　/　演示数据</p></div>`;
}
function shareMarkup(){return `<div class="share-layout"><div id="share-preview">${shareCardMarkup()}</div><div class="share-options"><fieldset><legend>卡片样式</legend><div class="segmented" role="group" aria-label="卡片主题"><button data-card-theme="light" aria-pressed="${!state.shareDark}">浅色</button><button data-card-theme="dark" aria-pressed="${state.shareDark}">深色</button></div></fieldset><fieldset><legend>展示内容</legend><label><input type="checkbox" id="share-titles" ${state.showTitles?'checked':''}>歌曲及歌手名称</label><label><input type="checkbox" id="share-counts" ${state.showCounts?'checked':''} ${state.showTitles?'':'disabled'}>歌曲播放次数</label></fieldset><p class="muted">范围与当前时长分布一致。可以隐藏歌曲信息，仅保留时长与趋势。</p><button class="primary" id="export-card">保存演示图片</button><p class="muted" id="export-status" role="status">PNG · 1080 × 1350<br>下载的图片会保留“演示数据”标记。</p></div></div>`;}
let inlineAnimation=null;
function animateInline(open){
  const box=$('#inline-detail'),from=box.hidden?0:box.getBoundingClientRect().height;
  const opacity=box.hidden?0:Number(getComputedStyle(box).opacity);
  inlineAnimation?.cancel();inlineAnimation=null;
  if(open){box.innerHTML=detailMarkup();box.hidden=false;$('.inline-close').onclick=()=>closePanel();}
  const to=open?box.scrollHeight:0;
  if(!from&&!open){box.hidden=true;box.innerHTML='';return;}
  box.style.overflow='hidden';
  const animation=box.animate([{height:`${from}px`,opacity},{height:`${to}px`,opacity:open?1:0}],
    {duration:matchMedia('(prefers-reduced-motion: reduce)').matches?0:300,easing:'cubic-bezier(.2,0,0,1)'});
  inlineAnimation=animation;
  animation.finished.then(()=>{if(inlineAnimation!==animation)return;inlineAnimation=null;box.style.overflow='';if(!open){box.hidden=true;box.innerHTML='';}}).catch(()=>{});
}
function closePanel(restore=true){
  state.panel=null;animateInline(false);$('#panel').close();$('#workspace').classList.remove('drawer-open');renderChart();
  if(restore&&opener?.isConnected)opener.focus();else if(restore)$('[data-review="detail"]').focus();
}
function presentPanel(){
  const kind=state.panel;if(!kind)return;
  const panel=$('#panel');panel.close();$('#workspace').classList.remove('drawer-open');
  if(kind==='detail'&&state.variant==='a'){
    animateInline(true);
  }else{
    inlineAnimation?.cancel();inlineAnimation=null;$('#inline-detail').hidden=true;$('#inline-detail').innerHTML='';
    panel.classList.toggle('drawer',state.variant==='b');
    $('#panel-title').textContent=kind==='detail'?'歌曲明细':'分享统计卡片';
    $('#panel-caption').textContent=kind==='detail'?'选择其他柱子，可继续查看不同日期或时段':'预览展示效果，可调整内容后保存演示图片';
    $('#panel-body').innerHTML=kind==='detail'?detailMarkup():shareMarkup();
    if(state.variant==='b'&&innerWidth>1000){$('#workspace').classList.add('drawer-open');panel.show();}else panel.showModal();
    if(kind==='share')bindShare();
  }
  $$('.go-ranking').forEach(b=>b.onclick=()=>{state.scope='range';closePanel(false);setPage('ranking');});
  renderChart();
}
function openDetail(index,trigger){state.index=index;state.panel='detail';opener=trigger||document.activeElement;presentPanel();$('#status').textContent=`已展示${detailLabel()}的歌曲明细`;}
function openShare(){opener=document.activeElement;state.panel='share';$('#more-menu').hidden=true;$('#more').setAttribute('aria-expanded','false');presentPanel();}
function bindShare(){
  $$('[data-card-theme]').forEach(b=>b.onclick=()=>{state.shareDark=b.dataset.cardTheme==='dark';updateShare();});
  $('#share-titles').onchange=e=>{state.showTitles=e.target.checked;updateShare();};
  $('#share-counts').onchange=e=>{state.showCounts=e.target.checked;updateShare();};
  $('#export-card').onclick=saveCard;
}
function updateShare(){
  $('#share-preview').innerHTML=shareCardMarkup();$('#share-counts').disabled=!state.showTitles;
  $$('[data-card-theme]').forEach(b=>b.setAttribute('aria-pressed',(b.dataset.cardTheme==='dark')===state.shareDark));
}
function saveCard(){
  const canvas=document.createElement('canvas');canvas.width=1080;canvas.height=1350;const ctx=canvas.getContext('2d');
  const dark=state.shareDark,d=current(),minutes=total(d.values),fg=dark?'#e7edf6':'#243247',muted=dark?'#a0b0c5':'#718197';
  ctx.fillStyle=dark?'#1f2937':'#f7f9fc';ctx.fillRect(0,0,1080,1350);
  function text(value,x,y,size,color=fg){ctx.font=`${size}px "Segoe UI","Microsoft YaHei",sans-serif`;ctx.fillStyle=color;ctx.fillText(value,x,y);}
  text('我的听歌记录',80,104,28,muted);text(d.range,80,158,28,muted);text(duration(minutes),80,292,76);text(`${d.days} 个听歌日 · 每一天，都有音乐`,80,356,28,muted);
  const max=Math.max(...d.values),step=920/d.values.length;
  d.values.forEach((v,i)=>{ctx.fillStyle='#80b6ef';ctx.fillRect(80+i*step,574-v/max*145,step*.72,v/max*145);});
  if(state.showTitles){text('这段时间最常听 · TOP 5',80,673,25,muted);tracks.forEach((t,i)=>{text(`${i+1}　${t.title}`,80,750+i*87,32);text(t.artist,390,750+i*87,25,muted);if(state.showCounts)text(`${rangeCount(t)}次`,905,750+i*87,28);});}
  text('听歌统计 / 演示数据',80,1267,25,muted);
  canvas.toBlob(blob=>{if(!blob)return;const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='听歌统计-分享卡片-演示.png';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);$('#export-status').textContent='演示图片已生成，下载由浏览器处理。';},'image/png');
}
function setPage(page){state.page=page;$('#overview').hidden=page!=='overview';$('#ranking').hidden=page!=='ranking';$$('[data-page]').forEach(b=>{b.classList.toggle('active',b.dataset.page===page);if(b.dataset.page===page)b.setAttribute('aria-current','page');else b.removeAttribute('aria-current');});renderRanking();$('.page-nav').scrollIntoView({block:'start',behavior:'smooth'});}
function setVariant(variant){state.variant=variant;$$('[data-variant]').forEach(b=>b.setAttribute('aria-pressed',b.dataset.variant===variant));$('#variant-note').textContent=variant==='a'?'方案 A：明细在图表下方展开，会将听歌小结向下推；分享使用居中弹窗。审核重点：展开长度、图表与明细的上下关系。':'方案 B：宽窗口使用右侧抽屉，图表与明细并排，正文可用宽度会缩小；窄窗口自动使用覆盖式面板。审核重点：抽屉宽度、正文压缩和窄窗口体验。';const url=new URL(location.href);url.searchParams.set('variant',variant);history.replaceState(null,'',url);if(state.panel)presentPanel();}
$$('[data-variant]').forEach(b=>b.onclick=()=>setVariant(b.dataset.variant));
$$('[data-page]').forEach(b=>b.onclick=()=>{closePanel(false);setPage(b.dataset.page);});
$$('[data-scope]').forEach(b=>b.onclick=()=>{state.scope=b.dataset.scope;renderRanking();});
$('#rank-mode').onchange=e=>{state.rankMode=e.target.value;renderRanking();};$('#search').oninput=e=>{state.query=e.target.value;renderRanking();};
$('#edit-range').onclick=()=>{closePanel(false);setPage('overview');$('#distribution').scrollIntoView({block:'start',behavior:'smooth'});$('#range').focus({preventScroll:true});};
$('#range').onchange=e=>{state.range=e.target.value;state.index=state.range==='day'?20:4;renderChart();renderRanking();if(state.panel)presentPanel();};
$('#compare').onchange=e=>{state.compare=e.target.checked;renderChart();};
$('#more').onclick=()=>{const open=$('#more-menu').hidden;$('#more-menu').hidden=!open;$('#more').setAttribute('aria-expanded',open);};$('#share-menu').onclick=openShare;
$('#close-panel').onclick=()=>closePanel();$('#panel').addEventListener('cancel',e=>{e.preventDefault();closePanel();});
$('#panel').addEventListener('click',e=>{if(e.target===$('#panel')){const r=$('#panel').getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)closePanel();}});
document.addEventListener('keydown',e=>{if(e.key==='Escape'){if(state.panel)closePanel();$('#more-menu').hidden=true;$('#more').setAttribute('aria-expanded','false');}});
document.addEventListener('click',e=>{if(!e.target.closest('.menu-anchor')){$('#more-menu').hidden=true;$('#more').setAttribute('aria-expanded','false');}});
$('#theme').onclick=()=>{document.body.classList.toggle('dark');$('#theme').textContent=document.body.classList.contains('dark')?'查看浅色':'查看深色';};
$$('[data-review]').forEach(b=>b.onclick=()=>{const action=b.dataset.review;if(action==='share'){openShare();return;}closePanel(false);if(action==='ranking'){state.scope='range';setPage('ranking');return;}setPage('overview');if(action==='detail')openDetail(state.range==='day'?20:4,b);if(action==='compare'){$('#compare').checked=true;state.compare=true;renderChart();}$('#distribution').scrollIntoView({block:'start',behavior:'smooth'});});
let wide=innerWidth>1000;window.addEventListener('resize',()=>{setReviewHeight();const next=innerWidth>1000;if(next!==wide&&state.panel&&state.variant==='b')presentPanel();wide=next;});
renderHeatmap();renderChart();renderRanking();setReviewHeight();setVariant(state.variant);

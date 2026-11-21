'use strict';
const $ = (s) => document.querySelector(s),
    $$ = (s) => [...document.querySelectorAll(s)],
    ico = (n, cls = '') => `<svg class="ico ${cls}" aria-hidden="true"><use href="#i-${n}"></use></svg>`;
const esc = (s) =>
    String(s ?? '').replace(
        /[&<>"']/g,
        (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c],
    );
const sourceDefs = [
    {
        id: 'film4k',
        num: 1,
        name: 'Tú Nam',
        kind: 'movie',
        desc: 'Nguồn phim 1 · Phim lẻ & phim bộ',
        sign: 'T',
    },
    {
        id: 'aether',
        num: 2,
        name: 'Thúy An',
        kind: 'movie',
        desc: 'Nguồn Aether · Nhiều server · Vietsub',
        sign: 'A',
    },
    { id: 'kkphim', num: 3, name: 'Cốm', kind: 'movie', desc: 'Nguồn phim 3 · Kho phim đa dạng', sign: 'C' },
    {
        id: 'miruro',
        num: 4,
        name: 'Anime Miruro',
        kind: 'anime',
        desc: 'Anime · Sub/Dub · Dịch phụ đề AI',
        sign: 'M',
    },
];
function read(key, fallback) {
    try {
        const x = JSON.parse(localStorage.getItem(key));
        return x == null ? fallback : x;
    } catch (e) {
        return fallback;
    }
}
function write(key, value) {
    try {
        localStorage.setItem(key, JSON.stringify(value));
    } catch (e) {}
}
function sourceLogo(id) {
    const glyphs = {
        film4k: `<path d="M8 29c5-10 14-16 29-17" fill="none" stroke="#f32d49" stroke-width="3" stroke-linecap="round"/><path d="M11 15h20v4h-8v13h-5V19h-7z" fill="#fcfcff"/><path d="M28 19h6v13h-6z" fill="#f32d49"/>`,
        aether: `<path d="M10 31 21 12h5l9 19h-6l-2-4H18l-2 4z" fill="#f8f8fc"/><path d="M23 15 31 31h-6l-3-7z" fill="#f32d49"/><path d="M15 34h21" stroke="#f32d49" stroke-width="2" stroke-linecap="round"/>`,
        kkphim: `<path d="M32 13c-3-3-7-4-11-3-9 2-14 12-10 20 4 9 15 9 22 3l-3-5c-4 4-10 4-13 0-4-5 0-12 5-13 3-1 5 0 7 2z" fill="#f8f8fc"/><path d="m23 19 10 5-10 5z" fill="#f32d49"/>`,
        miruro: `<path d="M6 32 16 11h4l5 10 5-10h5l4 21h-6l-2-11-6 10h-2l-6-11-5 12z" fill="#f9f9fc"/><path d="M27 29 35 13l3 19h-6l-1-5z" fill="#f32d49"/><path d="M8 35 37 9" stroke="#f32d49" stroke-width="2" opacity=".85"/>`,
    };
    return `<svg viewBox="0 0 44 44" class="source-logo source-logo-${esc(id)}" aria-hidden="true"><rect x="2" y="2" width="40" height="40" rx="11" fill="#0d0e12" stroke="#5a1c28" stroke-width="1.2"/><path d="M5 11V7q0-2 2-2h4" stroke="#fa3850" stroke-width="1.5" fill="none" stroke-linecap="round"/>${glyphs[id] || glyphs.film4k}</svg>`;
}

function sourceName() {
    return source(state.source).name;
}
function renderNav() {
    const items = [
        ['home', 'home', 'Trang chủ'],
        ['search', 'search', 'Tìm kiếm'],
        ['browse', 'grid', 'Danh mục'],
    ];
    $('#tvNav').innerHTML = items
        .map(
            ([id, iconName, label]) =>
                `<button class="tv-nav-row ${state.page === id ? 'active' : ''}" title="${label}" data-act="${id}">${ico(iconName)}<span>${label}</span></button>`,
        )
        .join('');
    $('#tvSourceNav').innerHTML = sourceDefs
        .map(
            (s) =>
                `<button type="button" class="tv-source-row ${state.source === s.id ? 'active' : ''}" data-act="changeSource" data-id="${s.id}" title="Nguồn ${s.num} · ${esc(s.name)}">${sourceLogo(s.id)}<span class="tv-source-name"><b>${esc(s.name)}</b><small>Nguồn ${s.num}</small></span>${state.source === s.id ? '<span class="source-active-mark"></span>' : ''}</button>`,
        )
        .join('');
    $('#mobileBottom').innerHTML = [...items, ['sources', 'tv', 'Nguồn']]
        .map(
            ([id, iconName, label]) =>
                `<button class="${state.page === id ? 'active' : ''}" data-act="${id}">${ico(iconName)}<span>${label}</span></button>`,
        )
        .join('');
}
function wordmark() {
    return `<span class="wordmark-red">AnPi</span><span class="wordmark-white">nime</span>`;
}
function renderHeader() {
    const s = source(state.source);
    $('#tvHeader').innerHTML =
        `<div class="tv-wordmark">${wordmark()}</div><span class="tv-divider"></span><div class="tv-kicker">${state.page === 'home' ? 'Khám phá' : state.page === 'search' ? 'Tìm kiếm' : state.page === 'browse' ? 'Danh mục' : state.page === 'settings' ? 'Cài đặt' : 'Nội dung'}</div><div class="header-spacer"></div><button class="icon-only" title="Tìm kiếm" data-act="search">${ico('search', 'lg')}</button><span class="tv-current-source">${sourceLogo(s.id)} ${esc(s.name)}</span>`;
    $('#mobileTop').innerHTML =
        `<div class="mobile-head-brand">${wordmark()}</div><div class="mobile-head-actions"><button class="mobile-source" data-act="sources" aria-label="Chọn nguồn phim">${sourceLogo(s.id)}<span>${esc(s.name)}</span>${ico('down', 'sm')}</button><button class="icon-only mobile-search" data-act="search" title="Tìm kiếm">${ico('search')}</button></div>`;
}
function sourceOption(s) {
    return `<button class="source-choice ${s.id === state.source ? 'active' : ''}" data-act="changeSource" data-id="${s.id}">${sourceLogo(s.id)}<span><div class="source-name">${esc(s.name)}</div><div class="source-caption">${esc(s.desc)}</div></span>${s.id === state.source ? `<span class="check">${ico('check')}</span>` : ''}</button>`;
}
function fmt(s) {
    const h = Math.floor(s / 3600),
        m = Math.floor((s % 3600) / 60),
        ss = s % 60;
    return h
        ? `${h}:${String(m).padStart(2, '0')}:${String(ss).padStart(2, '0')}`
        : `${String(m).padStart(2, '0')}:${String(ss).padStart(2, '0')}`;
}
function toast(msg) {
    const el = $('#toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), 2800);
}
function sheet(title, subtitle, body) {
    return `<div class="sheet" role="dialog" aria-modal="true" aria-label="${esc(title)}"><div class="sheet-top"><div><h2>${esc(title)}</h2><p>${esc(subtitle)}</p></div><button class="sheet-close" data-act="close" aria-label="Đóng">${ico('close')}</button></div>${body}</div>`;
}
let tvMenuOpen = false,
    menuHideTimer;
function openTvMenu(focus = false) {
    if (state.mode !== 'tv' || state.page === 'player' || state.overlay) return;
    clearTimeout(menuHideTimer);
    tvMenuOpen = true;
    device.classList.add('tv-menu-open');
    const rail = $('#tvRail');
    rail.inert = false;
    $('#tvEdge').setAttribute('aria-expanded', 'true');
    if (focus) {
        requestAnimationFrame(() => {
            if (tvMenuOpen)
                ($('#tvNav .tv-nav-row.active') || $('#tvNav button'))?.focus({ preventScroll: true });
        });
    }
}
function closeTvMenu() {
    clearTimeout(menuHideTimer);
    tvMenuOpen = false;
    device.classList.remove('tv-menu-open');
    const rail = $('#tvRail');
    if (rail) rail.inert = true;
    const edge = $('#tvEdge');
    if (edge) edge.setAttribute('aria-expanded', 'false');
}
$('#tvEdge').addEventListener('mouseenter', () => openTvMenu());
$('#tvEdge').addEventListener('focus', () => openTvMenu());
$('#tvEdge').addEventListener('click', () => openTvMenu(true));
$('#tvRail').addEventListener('mouseenter', () => clearTimeout(menuHideTimer));
$('#tvRail').addEventListener('mouseleave', () => {
    menuHideTimer = setTimeout(() => {
        if (!$('#tvRail').contains(document.activeElement)) closeTvMenu();
    }, 180);
});
document.addEventListener('pointerdown', (e) => {
    if (tvMenuOpen && !e.target.closest('#tvRail,#tvEdge')) closeTvMenu();
});
document.addEventListener('focusin', (e) => {
    if (tvMenuOpen && state.mode === 'tv' && !e.target.closest('#tvRail,#tvEdge') && !state.overlay)
        closeTvMenu();
});
function moveFocus(direction) {
    const root = state.overlay ? $('#overlay') : tvMenuOpen ? $('#tvRail') : container();
    const active = document.activeElement;
    const browseTv = state.mode === 'tv' && !state.overlay && !playerScreen && !tvMenuOpen;
    const bounds = active?.getBoundingClientRect();
    if (browseTv && direction === 'left' && bounds?.left < device.clientWidth * 0.075) {
        openTvMenu(true);
        return;
    }
    if (browseTv && direction === 'up' && !active?.closest('#tvHeader') && bounds?.top < 80) {
        $('#tvHeader button')?.focus();
        return;
    }
    const buttons = [
        ...root.querySelectorAll('button:not(:disabled),input:not(:disabled),select:not(:disabled)'),
    ].filter(
        (element) => element.getClientRects().length && getComputedStyle(element).visibility === 'visible',
    );
    if (!buttons.includes(active) && !(active?.closest('#tvHeader') && state.mode === 'tv')) {
        if (direction === 'left' && state.mode === 'tv' && !state.overlay && !playerScreen && !tvMenuOpen) {
            openTvMenu(true);
        } else {
            (buttons.find((element) => element.classList.contains('active')) || buttons[0])?.focus();
        }
        return;
    }
    const center = (element) => {
        const bounds = element.getBoundingClientRect();
        return [bounds.left + bounds.width / 2, bounds.top + bounds.height / 2];
    };
    const [x, y] = center(active);
    const horizontal = direction === 'left' || direction === 'right';
    const sign = direction === 'left' || direction === 'up' ? -1 : 1;
    let best,
        bestScore = Infinity;
    for (const candidate of buttons) {
        if (candidate === active) continue;
        if (horizontal && !active.closest('.poster-row') && candidate.closest('.poster-row')) {
            const row = candidate.closest('.poster-row').getBoundingClientRect();
            const rect = candidate.getBoundingClientRect();
            if (rect.right <= row.left || rect.left >= row.right) continue;
        }
        const [cx, cy] = center(candidate);
        const primary = sign * (horizontal ? cx - x : cy - y);
        const secondary = Math.abs(horizontal ? cy - y : cx - x);
        if (primary <= 4) continue;
        const score = primary + secondary * 2.5;
        if (score < bestScore) {
            best = candidate;
            bestScore = score;
        }
    }
    if (tvMenuOpen && direction === 'right' && !best) {
        closeTvMenu();
        container().querySelector('button:not(:disabled),input')?.focus();
    } else if (best) {
        best.focus({ preventScroll: true });
        best.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    } else if (direction === 'left' && state.mode === 'tv' && !state.overlay && !playerScreen) {
        openTvMenu(true);
    }
}

const params = new URLSearchParams(location.search);
const playerScreen = params.get('screen') === 'player';
const preview = $('#preview'),
    device = $('#device');
const state = {
    mode: params.get('mode') === 'phone' ? 'phone' : 'tv',
    page: playerScreen ? 'player' : 'home',
    source: 'aether',
    hero: 0,
    origin: 'home',
    server: 'auto',
    selected: null,
    detail: null,
    episode: null,
    home: [],
    categories: [],
    results: [],
    filter: '',
    query: '',
    error: '',
    busy: false,
    pageNumber: 1,
    more: false,
    overlay: null,
    favorites: read('anpinime_v8_favorites', []),
    preferences: {},
    player: null,
};
const source = (id) => sourceDefs.find((item) => item.id === id) || sourceDefs[1];
const items = new Map();
const settingsOptions = {
    quality: ['Tối đa 1080p', 'Tối đa 720p', 'Tự động'],
    subtitleSize: ['Nhỏ', 'Vừa', 'Lớn', 'Rất lớn'],
    aspect: ['Vừa màn hình', 'Tràn viền', 'Kéo giãn', 'Phóng to 112%', 'Khớp chiều ngang'],
    speed: ['0.75x', '1.0x', '1.25x', '1.5x', '2.0x'],
};
const defaults = { quality: 0, subtitleSize: 2, aspect: 0, speed: 1 };
const requests = new Map();
let requestId = 0,
    epoch = 0,
    searchTimer,
    toastTimer,
    restoreFocus,
    pendingPlay;

if (window.AnPinime) {
    AnPinime.onmessage = (event) => {
        const result = JSON.parse(event.data);
        const pending = requests.get(result.id);
        if (!pending) return;
        clearTimeout(pending.timeout);
        requests.delete(result.id);
        if (result.error) pending.reject(new Error(result.error));
        else pending.resolve(result.data);
    };
}

function native(action, data = {}) {
    if (!window.AnPinime) return Promise.reject(new Error('Không kết nối được với ứng dụng.'));
    const id = ++requestId;
    return new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
            requests.delete(id);
            reject(new Error('Nguồn phản hồi quá lâu. Hãy thử lại.'));
        }, 60000);
        requests.set(id, { resolve, reject, timeout });
        AnPinime.postMessage(JSON.stringify({ id, action, source: state.source, ...data }));
    });
}

function mediaUrl(raw) {
    try {
        const url = new URL(raw);
        return /^https?:$/.test(url.protocol) ? url.href : '';
    } catch (_) {
        return '';
    }
}

function imgBg(raw) {
    const url = mediaUrl(raw).replace(/['()]/g, (char) => '%' + char.charCodeAt(0).toString(16));
    return (
        'background-image:linear-gradient(90deg,#0b101a66,#0b101a0b),' +
        (url ? `url('${esc(url)}'),` : '') +
        'linear-gradient(135deg,#242c45,#51263c)'
    );
}

function register(raw) {
    const id = JSON.stringify([raw.source, raw.url]);
    const item = {
        id,
        raw,
        title: raw.title,
        sub: raw.subtitle,
        year: (raw.info || '').match(/\b(?:19|20)\d{2}\b/)?.[0],
        rating: raw.score,
        quality: raw.quality,
        duration: raw.episodeText,
        poster: raw.imageUrl,
        backdrop: raw.bannerUrl || raw.imageUrl,
        desc: raw.summary,
        info: raw.info,
        kind: source(raw.source).kind,
    };
    items.set(id, item);
    return item;
}

function meta(item) {
    return [
        item.rating && `<span class="pill star">★ ${esc(item.rating)}</span>`,
        item.year && `<span>${esc(item.year)}</span>`,
        item.duration && `<span>${esc(item.duration)}</span>`,
        item.quality && `<span class="pill">${esc(item.quality)}</span>`,
    ]
        .filter(Boolean)
        .join('');
}

function poster(item) {
    const image = mediaUrl(item.poster);
    return `<button type="button" class="poster" data-act="detail" data-id="${esc(item.id)}" title="Chi tiết ${esc(item.title)}">
        <div class="poster-art" style="--fallback:#51263c">
            <div class="poster-fallback">${esc(item.title)}</div>
            ${image ? `<img src="${esc(image)}" loading="lazy" alt="">` : ''}
            ${item.quality ? `<span class="poster-top-badge">${esc(item.quality)}</span>` : ''}
            ${item.rating ? `<span class="poster-bottom-badge">★ ${esc(item.rating)}</span>` : ''}
        </div>
        <div class="poster-title">${esc(item.title)}</div>
        <div class="poster-note">${esc([item.year, item.sub || item.duration].filter(Boolean).join(' · '))}</div>
    </button>`;
}

function section(title, movies, tag, index = null) {
    const more =
        index === null
            ? ''
            : `<button class="textlink" data-act="section" data-index="${index}">Xem tất cả ${ico('chevron', 'sm')}</button>`;
    return `<section class="section"><div class="section-header"><div><span class="section-kicker">${esc(tag)}</span>
        <h2 class="section-headline">${esc(title)}</h2></div>${more}</div>
        <div class="poster-row">${movies.map(poster).join('')}</div></section>`;
}

const saved = (item) =>
    state.favorites.some((favorite) => favorite.source === item.raw.source && favorite.url === item.raw.url);
function movieAction(action, item, label, icon, className) {
    return `<button class="cta ${className}" data-act="${action}" data-id="${esc(item.id)}" aria-label="${esc(label)}">${ico(icon)}${className.includes('circle') ? '' : ' ' + esc(label)}</button>`;
}

function hero() {
    const featured = state.home[0]?.items.slice(0, 5) || [];
    const item = featured[state.hero] || featured[0];
    if (!item) return '';
    return `<div class="hero"><div class="hero-img" style="${imgBg(item.backdrop)}"></div>
        <div class="hero-fog" style="--fog:#51263c4d"></div><div class="hero-art-overlay"></div>
        <div class="hero-text"><div class="section-kicker">${item.kind === 'anime' ? 'MIRURO · ANIME ĐƯỢC QUAN TÂM' : 'ANPINIME · NỔI BẬT TRONG TUẦN'}</div>
        <h1 class="hero-title">${esc(item.title)}</h1><div class="hero-sub">${meta(item)}</div>
        <p class="hero-desc">${esc(item.desc || item.sub)}</p><div class="btn-row">
        ${movieAction('watch', item, 'Xem ngay', 'play', 'primary')}
        ${movieAction('detail', item, 'Chi tiết', 'info', 'ghost')}
        ${movieAction('favorite', item, saved(item) ? 'Bỏ lưu' : 'Lưu phim', saved(item) ? 'check' : 'plus', 'ghost circle')}</div></div>
        <div class="hero-slides">${featured.map((_, index) => `<button aria-label="Đề cử ${index + 1}" class="${state.hero === index ? 'active' : ''}" data-act="hero" data-index="${index}"></button>`).join('')}</div></div>`;
}

function status(label, retry = false) {
    return `<div class="empty" role="status"><b>${esc(label)}</b>${retry ? '<button class="cta ghost" data-act="retry">Thử lại</button>' : ''}</div>`;
}

function homePage() {
    if (!state.home.length)
        return `<div class="content-area">${status(state.error || (state.busy ? 'Đang tải phim…' : 'Nguồn chưa trả dữ liệu.'), !state.busy)}</div>`;
    const favorites = state.favorites.filter((item) => item.source === state.source).map(register);
    const rows = state.home.map((row, index) =>
        section(row.title, row.items, index === 0 ? 'DÀNH CHO BẠN' : 'KHÁM PHÁ', index),
    );
    return `<div class="tv-home"><div class="content-area">${hero()}
        ${favorites.length ? section('Phim đã lưu', favorites, 'DANH SÁCH CỦA BẠN') : ''}${rows.join('')}</div></div>`;
}

function intro(title, kicker, caption) {
    return `<div class="page-intro"><div class="section-kicker">${esc(kicker)}</div><h1>${esc(title)}</h1><p>${esc(caption)}</p></div>`;
}

function grid(movies) {
    const error = state.error && status(state.error, true);
    const empty = !movies.length && status(state.busy ? 'Đang tải…' : 'Chưa có kết quả');
    const next =
        state.more || (movies.length && state.busy)
            ? `<div class="load-more"><button class="cta ghost" data-act="more" ${state.busy ? 'disabled' : ''}>${state.busy ? 'Đang tải…' : 'Tải thêm'}</button></div>`
            : '';
    return `<div class="grid-cards">${movies.map(poster).join('')}${error || empty || ''}</div>${next}`;
}

function browsePage() {
    const tags = [{ title: 'Tất cả', path: '' }, ...state.categories];
    const filters = tags
        .map(
            ({ title, path }) =>
                `<button class="tag ${state.filter === path ? 'active' : ''}" data-act="filter" data-value="${esc(path)}">${esc(title)}</button>`,
        )
        .join('');
    return `<div class="content-area">${intro('Danh mục phim', 'BỘ LỌC THEO NGUỒN · ' + sourceName(), 'Khám phá nội dung theo danh mục của nguồn đang chọn.')}
        <div class="tag-row">${filters}</div><div id="resultsGrid" style="margin-top:14px">${grid(state.results)}</div></div>`;
}

function searchPage() {
    return `<div class="content-area">${intro('Tìm phim và anime', 'TÌM KIẾM / ' + sourceName(), 'Tìm kiếm trong thư viện của nguồn đang chọn.')}
        <label class="search-field">${ico('search', 'lg')}<input id="searchInput" autocomplete="off" value="${esc(state.query)}"
        placeholder="Tên phim, anime hoặc thể loại..." aria-label="Nhập từ khóa tìm kiếm"><span style="color:#727d8c;font-size:11px">↵</span></label>
        <div class="section" style="margin-top:26px"><div class="section-header"><h2 class="section-headline" id="resultTitle"></h2></div>
        <div id="resultsGrid"></div></div></div>`;
}

function updateResults() {
    const results = $('#resultsGrid');
    if (results) results.innerHTML = grid(state.results);
    const title = $('#resultTitle');
    if (title) title.textContent = state.query.trim() ? 'Kết quả trong ' + sourceName() : 'Gợi ý cho bạn';
}

function detailPage() {
    const item = { ...state.selected, ...state.detail };
    // Some sources supply only the catalog artwork.
    const cover = mediaUrl(item.poster || state.selected.poster);
    const backdrop = item.backdrop || state.selected.backdrop;
    const actions = `<button class="cta primary" data-act="play" ${state.episode === null ? 'disabled' : ''}>${ico('play')} Xem ngay</button>
        ${movieAction('favorite', state.selected, saved(state.selected) ? 'Đã lưu' : 'Lưu phim', saved(state.selected) ? 'check' : 'plus', 'ghost')}
        <button class="cta ghost" data-act="back">${ico('back')} Quay lại</button>`;
    const episodes = state.detail?.episodes || [];
    const groups = [...new Set(episodes.map((ep) => ep.group))];
    const servers = state.detail?.servers?.length
        ? state.detail.servers
        : groups.filter(Boolean).map((group) => ({ id: group, label: group }));
    const visible =
        servers.length && !state.detail?.servers?.length
            ? episodes.filter((ep) => ep.group === state.server)
            : episodes;
    const miruroLink = state.source === 'miruro' && state.error
        ? '<button class="cta ghost" data-act="openMiruro">Mở trang xem Miruro</button>'
        : '';
    const panel = !state.detail
        ? status(state.error || 'Đang tải danh sách tập…', !!state.error)
        : `<div class="block-panel">
        ${
            servers.length
                ? `<h3 class="block-title">Chọn nguồn phát</h3><div class="tag-row">${servers
                      .map(
                          ({ id, label }) =>
                              `<button class="tag ${state.server === id ? 'active' : ''}" data-act="server" data-value="${esc(id)}">${esc(label)}</button>`,
                      )
                      .join('')}</div>`
                : ''
        }
        <h3 class="block-title">Danh sách tập${visible.length ? ' (' + visible.length + ' tập)' : ''}</h3>
        <div class="episode-grid">${visible.map((ep) => `<button class="ep-btn ${state.episode === ep.index ? 'active' : ''}" data-act="episode" data-index="${ep.index}">${esc(ep.title)}</button>`).join('')}</div>
        ${visible.length ? '' : status('Nguồn chưa cung cấp tập phát.')}</div>`;
    return `<div class="detail-banner"><div class="detail-img" style="${imgBg(backdrop)}"></div></div>
        <div class="detail-layout"><div class="detail-top">
        ${cover ? `<img class="detail-cover" src="${esc(cover)}" alt="">` : '<div class="detail-cover"></div>'}
        <div class="detail-summary"><div class="section-kicker">${item.kind === 'anime' ? 'MIRURO · ANIME' : 'ĐIỆN ẢNH · ' + esc(sourceName())}</div>
        <h1>${esc(item.title)}</h1><div class="hero-sub">${meta(item)}</div>
        <p>${esc(item.desc)}</p><div class="btn-row">${actions}</div></div></div>
        <p class="mobile-detail-desc hidden">${esc(item.desc)}</p><div class="mobile-detail-actions hidden">${actions}</div>
        ${panel}${miruroLink}</div>`;
}

function settingsPage() {
    const controls = Object.entries({
        quality: 'Chất lượng',
        subtitleSize: 'Cỡ chữ phụ đề',
        aspect: 'Tỷ lệ khung hình',
        speed: 'Tốc độ phát',
    })
        .map(
            ([key, label]) => `<div class="setting-row"><label for="setting_${key}">${label}</label>
        <select id="setting_${key}" data-setting="${key}">${settingsOptions[key]
            .map(
                (option, index) =>
                    `<option value="${index}" ${(state.preferences[key] ?? defaults[key]) === index ? 'selected' : ''}>${option}</option>`,
            )
            .join('')}</select></div>`,
        )
        .join('');
    return `<div class="content-area">${intro('Cài đặt giao diện & phát phim', 'ANPINIME · TÙY CHỈNH', 'Tùy chỉnh trình phát và chọn nguồn nội dung.')}
      <div class="settings-grid"><section class="setting-box"><h3>Trình phát</h3>${controls}</section>
      <section class="setting-box"><h3>Nguồn nội dung</h3><div class="source-grid" style="margin:16px 0">${sourceDefs.map(sourceOption).join('')}</div>
      <button class="cta ghost" data-act="refresh">${ico('refresh')} Làm mới dữ liệu</button>
      <button class="cta ghost" data-act="replay">${ico('play')} Xem intro</button>
      <button class="cta ghost" data-act="update">Kiểm tra cập nhật</button></section></div></div>`;
}

const playerOptions = [
    ['server', 'tv', 'Server'],
    ['subtitle', 'sub', 'Phụ đề'],
    ['translate', 'spark', 'Dịch AI'],
    ['audio', 'audio', 'Âm thanh'],
    ['aspect', 'expand', 'Tỉ lệ'],
    ['settings', 'settings', 'Cài đặt'],
];

function showPhonePlayerMenu() {
    const options = playerOptions
        .filter(([action]) => action !== 'translate' || ['aether', 'miruro'].includes(state.player?.source))
        .map(([action, icon, label]) =>
            `<button class="sheet-option" data-act="${action}">${ico(icon)} <b>${label}</b></button>`);
    openOverlay('Điều khiển phát', '', `<div class="sheet-options">${options.join('')}</div>`);
    native('menuOpened').catch((error) => toast(error.message));
}

function playerPage() {
    return `<div class="player-shell" data-act="toggleHud">
        <div class="player-header"><button class="icon-only" data-act="exit" aria-label="Quay lại">${ico('back', 'lg')}</button>
        <div><div class="play-heading"></div><small id="playerSource"></small></div>
        <div class="player-header-spacer"></div><span id="playerQuality" class="pill color"></span></div>
        <div class="player-controls"><div class="player-timeline"><span id="nowTime">00:00</span>
        <input id="seekRange" type="range" value="0" min="0" max="0" aria-label="Tua video" disabled>
        <span id="endTime">00:00</span></div>
        <div class="player-actions">
            <button class="control-btn transport" data-act="rewind">${ico('rewind')} <span>-10s</span></button>
            <button class="control-btn transport play" data-act="togglePlay">${ico('play', 'lg')} <span>Phát</span></button>
            <button class="control-btn transport" data-act="forward">${ico('forward')} <span>+10s</span></button>
            <button class="control-btn transport" data-act="next">${ico('next')} <span>Tập tiếp</span></button>
            <button class="control-btn" data-act="episodes">${ico('list')} Tập</button>
            <button class="control-btn phone-player-menu" data-act="playerMenu">${ico('settings')} Tùy chọn</button>
            ${playerOptions
                .map(
                    ([action, icon, label]) =>
                        `<button class="control-btn player-option" data-act="${action}">${ico(icon)} ${label}</button>`,
                )
                .join('')}
        </div><div class="player-hint">${state.mode === 'tv' ? 'D-pad: ← / → để chuyển focus hoặc tua · OK để chọn' : 'Chạm để mở điều khiển · Kéo thanh thời lượng để tua'}</div></div></div>`;
}

function updatePlayer(data) {
    const previous = state.player;
    state.player = data;
    if (previous?.source !== data.source) {
        device.classList.toggle('miruro', data.source === 'miruro');
        $('.player-actions [data-act="translate"]').hidden = !['aether', 'miruro'].includes(data.source);
    }
    if (previous?.title !== data.title) $('.play-heading').textContent = data.title;
    if (previous?.episode !== data.episode || previous?.source !== data.source || previous?.server !== data.server) {
        $('#playerSource').textContent = [data.episode, source(data.source).name, data.server]
            .filter(Boolean).join(' · ');
    }
    if (previous?.quality !== data.quality) $('#playerQuality').textContent = data.quality;
    if (previous?.hud !== data.hud) {
        $('.player-header').classList.toggle('hidden', !data.hud);
        $('.player-controls').classList.toggle('hidden', !data.hud);
    }
    if (previous?.next !== data.next) $('[data-act="next"]').hidden = !data.next;
    if (previous?.translated !== data.translated) $('.player-actions [data-act="translate"]').classList.toggle('active', !!data.translated);
    const play = $('[data-act="togglePlay"]');
    if (previous?.playing !== data.playing) {
        play.innerHTML = `${ico(data.playing ? 'pause' : 'play', 'lg')} <span>${data.playing ? 'Dừng' : 'Phát'}</span>`;
    }
    const seek = $('#seekRange');
    const duration = Math.max(0, Math.floor(data.duration / 1000));
    if (!seek.matches(':active')) {
        seek.max = duration;
        seek.value = Math.floor(data.position / 1000);
        $('#nowTime').textContent = fmt(Number(seek.value));
    }
    seek.disabled = !duration;
    $('#endTime').textContent = fmt(duration);
    if (
        data.hud &&
        state.mode === 'tv' &&
        !state.overlay &&
        (!previous?.hud || !device.contains(document.activeElement))
    )
        play.focus();
}

function openOverlay(title, caption, body) {
    if (!state.overlay) restoreFocus = document.activeElement;
    state.overlay = true;
    $('#overlay').innerHTML = sheet(title, caption, body);
    $('#overlay').classList.remove('hidden');
    ($('#overlay .active') || $('#overlay button')).focus({ preventScroll: true });
}

function closeOverlay() {
    state.overlay = null;
    $('#overlay').classList.add('hidden');
    $('#overlay').replaceChildren();
    if (restoreFocus?.isConnected) restoreFocus.focus({ preventScroll: true });
}

function showSources() {
    openOverlay(
        'Chọn nguồn nội dung',
        '4 nguồn phim và anime',
        `<div class="sheet-options source-picker-grid">${sourceDefs.map(sourceOption).join('')}</div>
        <button class="cta ghost" data-act="settings">${ico('settings')} Cài đặt</button>`,
    );
}

function showPlayerOptions(data) {
    const choices = data.labels
        .map(
            (label, index) =>
                `<button class="sheet-option ${index === data.selected ? 'active' : ''}" data-act="choice" data-index="${index}">
        <span style="flex:1"><b>${esc(label)}</b></span>${index === data.selected ? `<span class="check">${ico('check')}</span>` : ''}</button>`,
        )
        .join('');
    openOverlay(data.title, data.message || '', `<div class="sheet-options">${choices}</div>`);
}

const container = () => (state.mode === 'phone' ? $('#mobileBody') : $('#tvPage'));
function render(reset = false) {
    const content = container();
    const oldScroll = reset ? 0 : content.scrollTop;
    const focused = document.activeElement;
    const key = focused?.dataset ? { ...focused.dataset } : {};
    preview.dataset.page = state.page;
    preview.dataset.mode = state.mode;
    preview.classList.toggle('is-playing', playerScreen);
    device.classList.toggle('miruro', state.source === 'miruro');
    if (!playerScreen) {
        renderNav();
        renderHeader();
    }
    $('#mobileBottom').classList.toggle('hidden', playerScreen);
    $('#tvHeader').classList.toggle('hidden', playerScreen);
    $('#mobileTop').classList.toggle('hidden', playerScreen);
    content.innerHTML = {
        home: homePage,
        browse: browsePage,
        search: searchPage,
        detail: detailPage,
        settings: settingsPage,
        player: playerPage,
    }[state.page]();
    content.scrollTop = oldScroll;
    if (state.page === 'search') updateResults();
    if (state.mode === 'tv' && !state.overlay && !playerScreen) {
        const buttons = [...content.querySelectorAll('button:not(:disabled),input')];
        const match =
            key.act &&
            buttons.find((button) =>
                ['act', 'id', 'value', 'index'].every((k) => key[k] === button.dataset[k]),
            );
        (match || (reset ? buttons[0] : null))?.focus({ preventScroll: true });
    }
}

function route(page, reset = true) {
    clearTimeout(searchTimer);
    epoch++;
    state.busy = false;
    state.error = '';
    state.page = page;
    closeTvMenu();
    closeOverlay();
    render(reset);
}

const homeCache = new Map(),
    categoryCache = new Map();
const unique = (movies) => [...new Map(movies.map((movie) => [movie.id, movie])).values()];
const homeMovies = () => unique(state.home.flatMap((row) => row.items));

async function loadHome(refresh = false) {
    const token = ++epoch,
        selectedSource = state.source;
    if (!refresh && homeCache.has(selectedSource)) {
        state.home = homeCache.get(selectedSource);
        render();
        return;
    }
    state.busy = true;
    state.error = '';
    render();
    try {
        const rows = await native('home', { refresh });
        if (token !== epoch) return;
        state.home = rows
            .map((row) => ({ ...row, items: row.items.map(register) }))
            .filter((row) => row.items.length);
        if (state.home.length) homeCache.set(selectedSource, state.home);
    } catch (error) {
        if (token === epoch) state.error = error.message;
    } finally {
        if (token === epoch) {
            state.busy = false;
            render();
        }
    }
}

async function loadCategories() {
    const selectedSource = state.source;
    try {
        const values = categoryCache.get(selectedSource) || (await native('categories'));
        if (values.length) categoryCache.set(selectedSource, values);
        if (state.page === 'browse' && state.source === selectedSource) {
            state.categories = values;
            render();
        }
    } catch (error) {
        if (state.page === 'browse' && selectedSource === state.source) toast(error.message);
    }
}

async function loadResults(append = false) {
    const token = ++epoch;
    const search = state.page === 'search';
    const query = state.query.trim();
    const page = append ? state.pageNumber + 1 : 1;
    state.error = '';
    if (!append) {
        state.results = [];
        state.more = false;
        state.pageNumber = 1;
    }
    if ((search && !query) || (!search && !state.filter)) {
        state.results = state.sectionItems || homeMovies();
        state.busy = !state.results.length;
        updateResults();
        if (state.busy) {
            try {
                const rows = await native('home');
                if (epoch === token) {
                    state.home = rows.map((row) => ({ ...row, items: row.items.map(register) }));
                    homeCache.set(state.source, state.home);
                    state.results = homeMovies();
                }
            } catch (error) {
                if (epoch === token) state.error = error.message;
            }
            if (epoch === token) state.busy = false;
        }
        updateResults();
        return;
    }
    state.busy = true;
    updateResults();
    try {
        const values = await native(search ? 'search' : 'browse', { query, path: state.filter, page });
        if (token !== epoch) return;
        const count = state.results.length;
        state.results = unique([...state.results, ...values.map(register)]);
        state.more = state.results.length > count;
        state.pageNumber = page;
    } catch (error) {
        if (token === epoch) state.error = error.message;
    } finally {
        if (token === epoch) {
            state.busy = false;
            updateResults();
        }
    }
}

function openList(page, path = '', sectionItems = null) {
    state.filter = path;
    state.sectionItems = sectionItems;
    state.results = [];
    state.more = false;
    route(page);
    if (page === 'browse') loadCategories();
    loadResults();
}

async function openDetail(item) {
    if (!item) return;
    if (state.page !== 'detail') {
        state.origin = state.page;
        state.returnScroll = container().scrollTop;
        state.returnItem = item.id;
    }
    state.selected = item;
    state.detail = null;
    state.episode = null;
    state.server = 'auto';
    route('detail');
    const token = epoch;
    try {
        const result = await native('detail', { item: item.raw });
        if (token !== epoch) return;
        state.detail = result;
        state.episode = result.episodes[0]?.index ?? null;
        state.server = result.servers[0]?.id ?? result.episodes[0]?.group ?? 'auto';
    } catch (error) {
        if (token === epoch) state.error = error.message;
    }
    if (token === epoch) render();
}

async function play() {
    if (pendingPlay || state.episode === null) return;
    pendingPlay = true;
    try {
        await native('play', { url: state.selected.raw.url, index: state.episode, server: state.server });
    } catch (error) {
        toast(error.message);
    } finally {
        pendingPlay = false;
    }
}

function selectSource(id) {
    if (!sourceDefs.some((item) => item.id === id)) return;
    state.source = id;
    state.hero = 0;
    state.home = homeCache.get(id) || [];
    state.categories = categoryCache.get(id) || [];
    state.query = '';
    route('home');
    native('source').catch((error) => toast(error.message));
    loadHome();
}

function back() {
    if (!$('#splash').classList.contains('hidden')) {
        finishIntro();
        return;
    }
    if (state.overlay) {
        closeOverlay();
        if (playerScreen) native('dismiss').catch((error) => toast(error.message));
        return;
    }
    if (playerScreen) {
        native('back').catch((error) => toast(error.message));
        return;
    }
    if (tvMenuOpen) {
        closeTvMenu();
        container().querySelector('button')?.focus();
        return;
    }
    if (state.page === 'detail') {
        route(state.origin, false);
        container().scrollTop = state.returnScroll;
        if (state.mode === 'tv')
            [...container().querySelectorAll('button')]
                .find((button) => button.dataset.id === state.returnItem)
                ?.focus({ preventScroll: true });
        return;
    }
    if (state.page !== 'home') {
        route('home');
        loadHome();
        return;
    }
    native('exit').catch((error) => toast(error.message));
}

function clickAction(button) {
    const { act, id, index, value } = button.dataset;
    if (playerScreen) {
        if (act === 'playerMenu') {
            showPhonePlayerMenu();
            return;
        }
        if (act === 'close') {
            back();
            return;
        }
        if (act === 'choice' || button.closest('#overlay')) closeOverlay();
        native(act, { index: Number(index) }).catch((error) => toast(error.message));
        return;
    }
    switch (act) {
        case 'home':
            route('home');
            loadHome();
            break;
        case 'search':
        case 'browse':
            openList(act);
            break;
        case 'settings':
            route('settings');
            break;
        case 'sources':
            showSources();
            break;
        case 'changeSource':
            selectSource(id);
            break;
        case 'hero':
            state.hero = Number(index);
            render();
            break;
        case 'detail':
        case 'watch':
            openDetail(items.get(id));
            break;
        case 'favorite': {
            const item = items.get(id);
            if (!item) return;
            const remove = saved(item);
            state.favorites = remove
                ? state.favorites.filter((row) => row.source !== item.raw.source || row.url !== item.raw.url)
                : [item.raw, ...state.favorites].slice(0, 100);
            write('anpinime_v8_favorites', state.favorites);
            render();
            toast(remove ? 'Đã bỏ lưu phim' : 'Đã lưu phim');
            break;
        }
        case 'section': {
            const row = state.home[Number(index)];
            if (row) openList('browse', row.path, row.items);
            break;
        }
        case 'filter':
            state.filter = value;
            state.sectionItems = null;
            render();
            loadResults();
            break;
        case 'more':
            if (!state.busy) loadResults(true);
            break;
        case 'retry':
            if (state.page === 'detail') openDetail(state.selected);
            else if (state.page === 'home') loadHome(true);
            else loadResults();
            break;
        case 'server':
            state.server = value;
            if (!state.detail.servers.length)
                state.episode = state.detail.episodes.find((ep) => ep.group === value)?.index ?? null;
            render();
            break;
        case 'episode':
            state.episode = Number(index);
            play();
            break;
        case 'play':
            play();
            break;
        case 'openMiruro':
            native('openMiruro', {url: state.selected.raw.url}).catch((error) => toast(error.message));
            break;
        case 'back':
            back();
            break;
        case 'close':
            closeOverlay();
            break;
        case 'refresh':
            route('home');
            loadHome(true);
            break;
        case 'replay':
            startIntro();
            break;
        case 'update':
            native('update').catch((error) => toast(error.message));
            break;
    }
}

let introTimer, introHide;
function finishIntro() {
    clearTimeout(introTimer);
    clearTimeout(introHide);
    const splash = $('#splash');
    splash.classList.add('finished');
    splash.querySelectorAll('video').forEach((video) => video.pause());
    introHide = setTimeout(() => splash.classList.add('hidden'), 460);
}
function startIntro() {
    clearTimeout(introTimer);
    clearTimeout(introHide);
    $('#splash').classList.remove('hidden', 'finished');
    const video = state.mode === 'phone' ? $('#splashPhoneVideo') : $('#splashTvVideo');
    video.currentTime = 0;
    video.onended = finishIntro;
    video.play().catch(finishIntro);
    introTimer = setTimeout(finishIntro, 4650);
}

function activate() {
    if (!$('#splash').classList.contains('hidden') && !$('#splash').classList.contains('finished')) {
        finishIntro();
        return;
    }
    const focus = document.activeElement;
    if (focus?.matches('button')) focus.click();
    else if (focus?.matches('input,select')) focus.click();
    else if (playerScreen) native('toggleHud').catch((error) => toast(error.message));
    else (container().querySelector('button,input') || $('#tvEdge')).focus();
}

window.anpinimeEvent = (event, data) => {
    if (event === 'back') back();
    else if (event === 'activate') activate();
    else if (event === 'player') updatePlayer(data);
    else if (event === 'selection') showPlayerOptions(data);
    else if (event === 'direction') moveFocus(data.direction);
};

document.addEventListener('click', (event) => {
    const button = event.target.closest('[data-act]');
    if (button && !event.target.closest('input,select')) clickAction(button);
    else if (event.target.id === 'overlay') back();
});
document.addEventListener(
    'error',
    (event) => {
        if (event.target instanceof HTMLImageElement) {
            if (event.target.classList.contains('detail-cover')) event.target.style.opacity = '0.25';
            else event.target.hidden = true;
        }
    },
    true,
);
document.addEventListener('input', (event) => {
    if (event.target.id === 'searchInput') {
        state.query = event.target.value;
        epoch++;
        clearTimeout(searchTimer);
        searchTimer = setTimeout(loadResults, 300);
    }
    if (event.target.id === 'seekRange') $('#nowTime').textContent = fmt(Number(event.target.value));
});
document.addEventListener('change', (event) => {
    const key = event.target.dataset.setting;
    if (key) {
        const value = Number(event.target.value);
        const previous = state.preferences[key] ?? defaults[key];
        state.preferences[key] = value;
        native('preference', { key, value }).catch((error) => {
            state.preferences[key] = previous;
            render();
            toast(error.message);
        });
    }
    if (event.target.id === 'seekRange')
        native('seek', { seconds: Number(event.target.value) }).catch((error) => toast(error.message));
});
document.addEventListener('keydown', (event) => {
    const element = document.activeElement;
    const text = element?.matches('input:not([type=range]), select');
    if (event.key === 'Escape' || (event.key === 'Backspace' && !text)) {
        event.preventDefault();
        back();
        return;
    }
    if (element?.id === 'searchInput' && event.key === 'Enter') {
        event.preventDefault();
        clearTimeout(searchTimer);
        element.blur();
        loadResults();
        return;
    }
    if (event.key === 'Enter' && !text) {
        event.preventDefault();
        if (!event.repeat) activate();
        return;
    }
    if (!event.key.startsWith('Arrow') || text) return;
    if (element?.id === 'seekRange' && ['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
    event.preventDefault();
    if (playerScreen && !state.player?.hud) {
        const action = { ArrowLeft: 'rewind', ArrowRight: 'forward' }[event.key] || 'toggleHud';
        native(action).catch((error) => toast(error.message));
    } else moveFocus(event.key.slice(5).toLowerCase());
});

document.body.classList.toggle('app-player', playerScreen);
document.documentElement.classList.toggle('app-player', playerScreen);
state.busy = !playerScreen;
render(true);
closeTvMenu();
if (!playerScreen && params.get('intro') === '1') startIntro();
native('ready')
    .then((data) => {
        if (playerScreen) updatePlayer(data);
        else {
            state.source = data.source;
            state.preferences = data.preferences;
            loadHome();
        }
    })
    .catch((error) => {
        state.busy = false;
        state.error = error.message;
        render();
    });

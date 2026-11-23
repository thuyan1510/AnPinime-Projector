// Use the same PLAYWRIGHT_MODULE and CHROMIUM_PATH as web-player.test.cjs.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const catalog = pathToFileURL(path.resolve(__dirname, '../app/src/main/assets/v8/index.html'));
catalog.search = '?mode=tv&intro=1';

function installCatalogFixture() {
    const summary = 'Nội dung phim dài. '.repeat(300);
    const items = Array.from({ length: 40 }, (_, i) => ({
        source: 'kkphim', url: 'https://example.test/phim/' + i, title: 'Phim ' + i,
        summary, imageUrl: '', subtitle: summary,
    }));
    const episodes = Array.from({ length: 120 }, (_, i) => ({
        title: 'Tập ' + (i % 60 + 1), index: i, group: i < 60 ? 'Vietsub' : 'Lồng tiếng',
    }));
    window.calls = [];
    document.addEventListener('playing', event => {
        if (event.target.id === 'introVideo') window.introPlayed = event.target.currentSrc;
    }, true);
    window.AnPinime = {
        postMessage(raw) {
            const { id, action, index, page, query } = JSON.parse(raw);
            window.calls.push({ action, page, query });
            let data;
            if (action === 'ready') data = { source: 'kkphim', preferences: {} };
            if (action === 'home') data = Array.from({ length: 5 }, (_, i) => ({ title: 'Xu hướng ' + i, path: '/movies', items }));
            if (action === 'categories') data = [{ title: 'Phim mới', path: '/movies' }];
            if (action === 'browse' || action === 'search') data = page <= 2 ? items.map(item => ({ ...item, url: item.url + '/' + page })) : [];
            if (action === 'detail') data = { title: 'Tên phim dài '.repeat(20), desc: summary, episodes };
            if (action === 'play') window.playedEpisode = index;
            setTimeout(() => this.onmessage({ data: JSON.stringify({ id, data }) }), action === 'browse' || action === 'search' ? 180 : 0);
        },
    };
}

async function checkLayout(browser, width, height, density) {
    const page = await browser.newPage({ viewport: { width, height }, isMobile: true, deviceScaleFactor: density });
    try {
        await page.addInitScript(installCatalogFixture);
        await page.goto(catalog.href);
        await page.locator('#splash.hidden').waitFor({ state: 'attached' });
        const posters = page.locator('#tvPage .poster-row').first().locator('button');
        await posters.first().waitFor();
        assert.equal(await posters.count(), 12);
        const fullCards = await posters.evaluateAll(cards => {
            const row = cards[0].parentElement.getBoundingClientRect();
            return cards.filter(card => card.getBoundingClientRect().right <= row.right + 1).length;
        });
        assert.equal(fullCards, 7);
        assert.match(await page.evaluate(() => window.introPlayed), /anpinime-intro-v4.mp4$/);
        assert.equal(await page.locator('video').evaluate(video => video.hasAttribute('src')), false);

        await posters.nth(1).hover();
        assert.ok(await posters.nth(1).evaluate(el => el === document.activeElement &&
            getComputedStyle(el).outlineStyle === 'none' &&
            getComputedStyle(el.querySelector('.poster-art'), '::before').borderTopWidth === '3px'));
        await posters.first().focus();
        await page.keyboard.press('ArrowLeft');
        await page.locator('.tv-menu-open').waitFor();
        assert.equal(await page.evaluate(() => document.activeElement.dataset.id), 'kkphim');
        assert.equal(await page.locator('.tv-brand-mark').innerText().then(text => text.replace(/\s/g, '')), 'AnPinime');
        await page.keyboard.press('ArrowRight');
        assert.equal(await posters.first().evaluate(card => card === document.activeElement), true);
        for (let i = 0; i < 4; i++) await page.keyboard.press('ArrowDown');
        assert.ok(await page.locator('#tvPage').evaluate(el => el.scrollTop > 0));
        for (let i = 0; i < 5; i++) await page.keyboard.press('ArrowUp');
        assert.equal(await page.evaluate(() => document.activeElement.dataset.act), 'watch');
        assert.equal(await page.locator('#tvPage').evaluate(el => el.scrollTop), 0);
        await posters.first().focus();
        for (let i = 0; i < 8; i++) await page.keyboard.press('ArrowRight');
        await page.keyboard.press('Enter');

        const episode = page.locator('.ep-btn').first();
        await episode.waitFor();
        for (const selector of ['.detail-cover', '.detail-summary [data-act=play]', '.ep-btn']) {
            const visible = await page.locator(selector).first().evaluate(el => {
                const box = el.getBoundingClientRect();
                return box.top >= 0 && box.height > 0 && box.bottom <= innerHeight;
            });
            assert.ok(visible, selector + ' outside initial viewport');
        }
        assert.equal(await page.locator('#tvPage').evaluate(el => el.scrollTop), 0);
        await page.locator('[data-act=server]').last().click();
        await episode.click();
        await page.waitForFunction(() => window.playedEpisode === 60);
        await page.keyboard.press('Escape');
        assert.equal(await page.evaluate(() => document.activeElement.dataset.id), 'kkphim');
        await page.keyboard.press('Escape');
        await page.locator('.detail-summary [data-act=back]').click();
        assert.equal(await posters.nth(8).evaluate(card => card === document.activeElement), true);

        for (const action of ['browse', 'search']) {
            await page.keyboard.press('Escape');
            await page.locator('#tvNav [data-act=' + action + ']').click();
            if (action === 'search') await page.locator('#searchInput').fill('phim');
            await page.waitForFunction(act => window.calls.some(call => call.action === act && call.page === 1), action);
            await page.locator('#sourceLoading:not(.hidden)').waitFor();
            const results = page.locator('#resultsGrid .poster');
            await page.waitForFunction(() => document.querySelectorAll('#resultsGrid .poster').length === 40);
            await results.last().evaluate(el => {
                window.lastPoster = el;
                el.focus({ preventScroll: true });
                el.scrollIntoView({ block: 'end' });
            });
            await page.waitForFunction(() => document.querySelectorAll('#resultsGrid .poster').length === 80);
            assert.ok(await page.evaluate(() => document.activeElement === window.lastPoster));
            assert.equal(await page.locator('[data-act=more]').count(), 0);
            await page.keyboard.press('ArrowDown');
            assert.equal(await page.evaluate(() => document.activeElement.dataset.position), '46');
            await results.last().evaluate(el => el.scrollIntoView({ block: 'end' }));
            await page.waitForFunction(act => window.calls.some(call => call.action === act && call.page === 3), action);
            await page.locator('#sourceLoading.hidden').waitFor({ state: 'attached' });
            assert.deepEqual(await page.evaluate(act => window.calls.filter(call => call.action === act).map(call => call.page), action), [1, 2, 3]);
        }
        await page.keyboard.press('Escape');
        await page.locator('#tvRail [data-id=miruro]').click();
        await page.locator('#tvPage .poster').first().waitFor();
        await page.keyboard.press('Escape');
        assert.equal(await page.evaluate(() => document.activeElement.dataset.id), 'miruro');
        assert.ok(await page.evaluate(() => parseInt(getComputedStyle(document.activeElement).outlineOffset) < 0));
        await page.locator('.tv-exit').click();
        assert.equal(await page.evaluate(() => window.calls.filter(call => call.action === 'exit').length), 1);
    } finally {
        await page.close();
    }
}

async function checkPhone(browser) {
    const page = await browser.newPage({ viewport: { width: 393, height: 852 }, isMobile: true, hasTouch: true });
    try {
        await page.addInitScript(installCatalogFixture);
        const phone = new URL(catalog);
        phone.searchParams.set('mode', 'phone');
        await page.goto(phone.href);
        await page.waitForFunction(() => window.introPlayed && document.querySelector('#splash.hidden'));
        await page.locator('#mobileBottom [data-act=browse]').tap();
        const posters = page.locator('#resultsGrid .poster');
        await posters.first().waitFor();
        await posters.last().evaluate(el => el.scrollIntoView({ block: 'end' }));
        await page.waitForFunction(() => document.querySelectorAll('#resultsGrid .poster').length === 80);
        await page.evaluate(() => window.anpinimeEvent('back'));
        assert.equal(await page.locator('#preview').getAttribute('data-page'), 'home');
    } finally {
        await page.close();
    }
}

(async () => {
    assert.ok(fs.existsSync(new URL(catalog)));
    const browser = await chromium.launch({
        executablePath: process.env.CHROMIUM_PATH, args: ['--no-sandbox', '--disable-dev-shm-usage'],
    });
    try {
        await checkLayout(browser, 1280, 720, 1);
        await checkLayout(browser, 640, 360, 2);
        await checkPhone(browser);
        console.log('Passed intro, TV focus and menu, banner return, episode selection and automatic pagination on TV and phone.');
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });

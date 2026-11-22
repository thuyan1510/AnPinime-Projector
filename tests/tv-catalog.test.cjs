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
    window.AnPinime = {
        postMessage(raw) {
            const { id, action, index } = JSON.parse(raw);
            let data;
            if (action === 'ready') data = { source: 'kkphim', preferences: {} };
            if (action === 'home') data = [{ title: 'Xu hướng', path: '', items }];
            if (action === 'detail') data = { title: 'Tên phim dài '.repeat(20), desc: summary, episodes };
            if (action === 'play') window.playedEpisode = index;
            setTimeout(() => this.onmessage({ data: JSON.stringify({ id, data }) }), 0);
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
        assert.equal(await page.locator('video').evaluate(video => video.currentSrc), '');

        await posters.first().focus();
        await page.keyboard.press('ArrowLeft');
        await page.locator('.tv-menu-open').waitFor();
        await page.keyboard.press('ArrowRight');
        assert.equal(await posters.first().evaluate(card => card === document.activeElement), true);
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
        assert.equal(await posters.nth(8).evaluate(card => card === document.activeElement), true);
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
        console.log('TV catalog layout, remote navigation and episode selection passed.');
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });

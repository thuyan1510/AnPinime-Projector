// Run: PLAYWRIGHT_MODULE=/path/to/playwright node tests/web-player.test.cjs
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const kotlin = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/anpinime/projector/PlayerActivity.kt'), 'utf8');
const script = kotlin.match(/val fallbackIsolateScript = """([\s\S]*?)"""\.trimIndent\(\)/)[1];
new vm.Script(script);
const videoFixture = `<video></video><script>
const v = document.querySelector('video');
let paused = true, position = 0;
Object.defineProperties(v, {
 duration: {get: () => 120}, paused: {get: () => paused},
 currentTime: {get: () => position, set: n => {position = n; v.dispatchEvent(new Event('timeupdate'));}}
});
v.play = () => {paused = false; v.dispatchEvent(new Event('playing')); return Promise.resolve();};
v.pause = () => {paused = true; v.dispatchEvent(new Event('pause'));};
</script>`;

(async () => {
    const browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_PATH || undefined,
        args: ['--no-sandbox']});
    try {
        const context = await browser.newContext();
        await context.addInitScript(() => {
            window.events = [];
            window.AnPinimeWebBridge = {
                onVideoFound: (...args) => events.push(['video', ...args]),
                onFrameFound: (...args) => events.push(['frame', ...args]),
                onPlayerReady: () => events.push(['ready']),
                onProgress: (...args) => events.push(['progress', ...args]),
            };
        });
        await context.addInitScript(script);
        await context.route('https://fixture.test/**', route => {
            const url = route.request().url();
            const body = url.endsWith('/frame')
                ? '<iframe style="width:640px;height:360px" src="https://frame.test/player"></iframe>'
                : url.endsWith('/challenge') ? '<title>Just a moment</title><form id="challenge-form">Verify</form>' : videoFixture;
            return route.fulfill({contentType: 'text/html', body});
        });
        await context.route('https://frame.test/**', route => route.fulfill({contentType:'text/html', body:videoFixture}));
        const page = await context.newPage();
        await page.goto('https://fixture.test/video');
        await page.waitForFunction(() => !document.querySelector('video').paused);
        const command = async (name, value) => {
            await page.evaluate(([name,value]) => window.postMessage({type:'AnPinimeCommand',name,value}, '*'), [name,value]);
        };
        await command('pause');
        await page.waitForFunction(() => document.querySelector('video').paused);
        // PlayerActivity.commitPendingSeek sends the absolute target in seconds.
        for (const position of [10, 20, 10]) {
            await command('seek', position);
            await page.waitForFunction(expected => document.querySelector('video').currentTime === expected, position);
        }
        assert.equal(await page.locator('video').evaluate(v => v.controls), false);
        await command('seek', 90);
        await page.waitForFunction(() => document.querySelector('video').currentTime === 90);
        await command('play');
        await page.waitForFunction(() => !document.querySelector('video').paused);
        await page.evaluate(() => fetch('/master.m3u8'));
        assert(await page.evaluate(() => events.some(e => e[0] === 'video' && e[1].endsWith('/master.m3u8'))));
        console.log('PASS autoplay, pause/play, repeated/absolute seeking, native stream discovery');

        await page.goto('https://fixture.test/frame');
        const frame = page.frames().find(f => f.url().startsWith('https://frame.test'));
        await frame.waitForFunction(() => !document.querySelector('video').paused);
        await command('pause'); await command('seek', 30);
        await frame.waitForFunction(() => document.querySelector('video').paused && document.querySelector('video').currentTime === 30);
        assert(await page.evaluate(() => events.some(e => e[0] === 'frame')));
        console.log('PASS cross-origin iframe discovery and app commands');

        await page.goto('https://fixture.test/challenge');
        const challenge = await page.locator('#challenge-form').evaluate(el => ({
            display: getComputedStyle(el).display, visibility: getComputedStyle(el).visibility,
            width: el.getBoundingClientRect().width, height: el.getBoundingClientRect().height,
            body: getComputedStyle(document.body).cssText
        }));
        assert(challenge.display !== 'none' && challenge.visibility !== 'hidden', JSON.stringify(challenge));
        console.log('PASS verification page remains visible');
    } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });

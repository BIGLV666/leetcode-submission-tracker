const {chromium} = require('playwright');
const trackerUrl = process.env.TRACKER_URL || 'http://127.0.0.1:8088';

async function capture(page, viewport, prefix) {
    await page.setViewportSize(viewport);
    await page.goto(trackerUrl, {waitUntil: 'networkidle'});
    await page.waitForSelector('#problem-table-body tr');
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    if (overflow) {
        throw new Error(`${prefix} overview has horizontal page overflow`);
    }
    await page.screenshot({path: `target/${prefix}-overview.png`, fullPage: true});

    await page.locator('#problem-table-body .problem-link').first().click();
    await page.waitForSelector('#problem-detail-dialog[open]');
    await page.waitForSelector('.submission-item');
    await page.screenshot({path: `target/${prefix}-detail.png`, fullPage: true});
    await page.getByRole('button', {name: '关闭详情'}).click();

    await page.getByRole('button', {name: '月度报告'}).click();
    await page.waitForSelector('.day-column');
    await page.waitForSelector('.day-value');
    const monthlyOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    if (monthlyOverflow) {
        throw new Error(`${prefix} monthly report has horizontal page overflow`);
    }
    await page.screenshot({path: `target/${prefix}-monthly.png`, fullPage: true});
}

(async () => {
    const browser = await chromium.launch({
        headless: true,
        executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
    });
    const page = await browser.newPage();
    const errors = [];
    page.on('console', message => {
        if (message.type() === 'error') {
            errors.push(message.text());
        }
    });
    page.on('pageerror', error => errors.push(error.message));

    await capture(page, {width: 1440, height: 960}, 'desktop');
    await capture(page, {width: 390, height: 844}, 'mobile');
    await browser.close();

    if (errors.length > 0) {
        throw new Error(errors.join('\n'));
    }
})();

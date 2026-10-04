// 把分享卡片 HTML 截成 PNG：node scripts/card_shot.cjs reports/share-2026-09.html [输出.png]
// 截取 #share 卡片元素本身（不含页面灰底），2x 分辨率适合社交媒体
const path = require('path');
const { chromium } = require('playwright');

(async () => {
  const [htmlPath, outArg] = process.argv.slice(2);
  if (!htmlPath) {
    console.error('用法：node scripts/card_shot.cjs reports/share-2026-09.html [输出.png]');
    process.exit(1);
  }
  const out = outArg || htmlPath.replace(/\.html$/i, '.png');
  const browser = await chromium.launch({ channel: 'chrome' });
  const page = await browser.newPage({
    viewport: { width: 1200, height: 1600 },
    deviceScaleFactor: 2,
  });
  await page.goto('file://' + path.resolve(htmlPath), { waitUntil: 'networkidle' });
  const card = page.locator('#share');
  await card.screenshot({ path: out });
  await browser.close();
  console.log('已导出：' + out);
})();

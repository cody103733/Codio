// Opens Codio for Windows' screens in a browser and saves a picture of each into ../out.
const { chromium } = require('playwright');

(async () => {
    const browser = await chromium.launch();
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
    const shots = [['home', '#/', 45000], ['movies', '#/movies', 15000], ['search', '#/search', 6000], ['library', '#/library', 6000], ['settings', '#/settings', 6000]];
    for (const [name, route, wait] of shots) {
        await page.goto('http://127.0.0.1:8091/' + route);
        await page.waitForTimeout(wait);
        await page.screenshot({ path: `../out/windows-${name}.png` });
        if (name === 'home') {
            await page.mouse.move(960, 800);
            await page.mouse.wheel(0, 700);
            await page.waitForTimeout(4000);
            await page.screenshot({ path: '../out/windows-home-rows.png' });
        }
    }
    await browser.close();
})();

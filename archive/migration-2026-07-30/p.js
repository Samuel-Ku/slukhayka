const puppeteer = require('puppeteer');

(async () => {
    const browser = await puppeteer.launch({
        args: ['--no-sandbox', '--disable-setuid-sandbox']
    });
    const page = await browser.newPage();
    page.on('request', request => {
        const url = request.url();
        if (url.includes('.m3u') || url.includes('.m3u8') || url.includes('.txt') || url.includes('.mp3') || url.includes('player') || url.includes('audio')) {
            console.log('REQUEST:', url);
        }
    });
    
    await page.goto('https://4read.org/7611-vkradi-mene-zaraz-mistichna-audiokniga-pro-petlju-chasu-ta-kohannja.html', { waitUntil: 'networkidle2' });
    await browser.close();
})();

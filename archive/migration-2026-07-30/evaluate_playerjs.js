const fs = require('fs');
let code = fs.readFileSync('playerjs6.js', 'utf8');

// The playerjs script defines a global class Playerjs.
// We can intercept the file initialization or find the decode function.

// Mock window and document
global.window = {
    location: { href: 'https://4read.org/' },
    addEventListener: () => {}
};
global.document = {
    createElement: () => ({ style: {}, appendChild: () => {}, setAttribute: () => {} }),
    getElementById: () => ({ style: {}, appendChild: () => {} }),
    getElementsByTagName: () => [],
    head: { appendChild: () => {} }
};
global.navigator = { userAgent: 'Mozilla/5.0' };
global.location = global.window.location;
global.screen = { width: 1920, height: 1080 };
global.localStorage = { getItem: () => null, setItem: () => {} };

try {
    eval(code);
    let player = new Playerjs({id: 'playerjs1', file: '{v1}7611-vkradi-mene-zaraz-mistichna-audiokniga-pro-petlju-chasu-ta-kohannja.m3u'});
    console.log("Player initialized");
} catch(e) {
    console.log("Error:", e.message);
}

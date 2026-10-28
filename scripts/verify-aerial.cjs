// npm install --no-save playwright
// node scripts/verify-aerial.cjs path/to/map.html [path/to/parcels.geojson]
// Set CHROMIUM_EXECUTABLE if Playwright's bundled Chromium is unavailable.
const { chromium } = require('playwright');
const fs = require('node:fs');
const http = require('node:http');
const assert = require('node:assert/strict');

const preview = process.argv[2];
if (!preview) throw new Error('Provide the generated map.html path.');
const metadata = require('../docs/osip-2024-tile-scheme.json');
const source = fs.readFileSync(preview, 'utf8');
// Expose existing map objects in the test page only.
const html = source.replace("L.control.layers({", "window.__test = { map, layer, aerial, osipCRS, parcels }; L.control.layers({");
const expected = process.argv[3] && JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
const report = { errors: [], networkFailures: [], imageryResponses: [] };
const server = http.createServer((req, res) => {
  res.setHeader('Content-Type', 'text/html; charset=utf-8'); res.end(html);
});

(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const proxyURL = process.env.HTTPS_PROXY || process.env.HTTP_PROXY;
  const browser = await chromium.launch({ headless: true,
    executablePath: process.env.CHROMIUM_EXECUTABLE || undefined,
    proxy: proxyURL ? { server: proxyURL, bypass: 'localhost,127.0.0.1' } : undefined,
    args: ['--no-sandbox']
  });
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 } });
  page.on('pageerror', error => report.errors.push(error.message));
  page.on('requestfailed', req => report.networkFailures.push({url:req.url(),error:req.failure().errorText}));
  page.on('response', res => {
    if (res.url().includes('/ImageServer/tile/')) report.imageryResponses.push({
      url:res.url(),status:res.status(),type:res.headers()['content-type']
    });
  });
  try {
    await page.goto('http://127.0.0.1:' + server.address().port, {waitUntil:'networkidle',timeout:45000});
    assert.equal(report.errors.length, 0, 'Unexpected JavaScript error');
    const parcels = await page.evaluate(() => window.__test.parcels);
    if (expected) assert.deepEqual(parcels, expected, 'Parcel data changed');
    assert.equal(await page.locator('.leaflet-overlay-pane path').count(), parcels.features.length);
    for (let i = 0; i < parcels.features.length; i++) {
      await page.evaluate(i => {
        const p=window.__test.layer.getLayers()[i];p.fire('click',{latlng:p.getBounds().getCenter()});
      }, i);
      const popup = await page.locator('.leaflet-popup-content').last().innerText();
      const p=parcels.features[i].properties;
      for (const field of ['TAXLOT','ACRES','OWNERNAME']) assert(popup.includes(String(p[field])));
    }
    report.parcelsAndPopups = 'PASS ('+parcels.features.length+' parcels)';
    const point = await page.evaluate(() => {
      const {map,layer}=window.__test;map.closePopup();
      const p=map.latLngToContainerPoint(layer.getLayers()[0].getCenter());return {x:p.x,y:p.y};
    });
    await page.waitForTimeout(400);
    await page.mouse.click(point.x,point.y);
    assert((await page.locator('.leaflet-popup-content').last().innerText()).includes(String(parcels.features[0].properties.TAXLOT)));
    report.pointerClick='PASS';
    const grid = await page.evaluate(info => {
      const {map,osipCRS,aerial}=window.__test;
      const lon=-122.96723279002941,lat=45.278118686585195;
      const x=6378137*lon*Math.PI/180,y=6378137*Math.log(Math.tan(Math.PI/4+lat*Math.PI/360));
      const checks=[1,10,13,16,18,19].map(z => {
        const r=info.lods[z].resolution,p=map.project([lat,lon],z);
        return {z,error:Math.max(Math.abs(p.x-(x-info.origin.x)/r),Math.abs(p.y-(info.origin.y-y)/r)),
          inverseError:Math.abs(osipCRS.zoom(osipCRS.scale(z+0.25))-(z+0.25))};
      });
      const urls=Object.values(aerial._tiles).map(t => ({url:t.el.src,coords:t.coords}));
      return {checks,urls};
    }, metadata.tileInfo);
    for (const check of grid.checks) { assert(check.error<1e-6);assert(check.inverseError<1e-10); }
    for (const tile of grid.urls) assert(tile.url.endsWith('/tile/'+tile.coords.z+'/'+tile.coords.y+'/'+tile.coords.x));
    report.cacheGrid='PASS (six zoom levels and actual requested tile addresses)';
    const decoded=await page.locator('.leaflet-tile').evaluateAll(imgs => imgs.filter(i=>i.complete&&i.naturalWidth===256&&i.naturalHeight===256).length);
    report.decodedImageryTiles=decoded;
    if (!decoded) {
      assert.equal(await page.locator('#imagery-status').getAttribute('data-state'),'error');
      assert(await page.locator('#retry-imagery').isVisible());
      const before=report.networkFailures.length;
      await page.locator('#retry-imagery').click();await page.waitForTimeout(1500);
      assert(report.networkFailures.length>before, 'Retry should issue new tile requests');
      report.failureAndRetry='PASS';
    }
    await page.evaluate(()=>window.__test.map.closePopup());
    await page.waitForTimeout(400);
    fs.mkdirSync('work',{recursive:true});
    await page.screenshot({path:'work/aerial-preview.png'});
    // A compact copy in a collapsed log group also permits visual review by
    // clients that can read job logs but cannot open binary artifact downloads.
    const thumbnail=await page.screenshot({type:'jpeg',quality:60});
    console.log('::group::Rendered preview thumbnail (base64 JPEG)');
    console.log('AERIAL_SCREENSHOT_DATA_URL=data:image/jpeg;base64,'+thumbnail.toString('base64'));
    console.log('::endgroup::');
    assert(decoded>0 && report.imageryResponses.some(r=>r.status===200&&/^image\//.test(r.type)),
      'INCOMPLETE: no actual imagery response decoded; do not claim aerial rendering works.');
    report.liveImagery='PASS';
  } catch(error) { report.result=error.message;process.exitCode=1; }
  finally {
    fs.mkdirSync('work',{recursive:true});fs.writeFileSync('work/aerial-verification.json',JSON.stringify(report,null,2));
    console.log(JSON.stringify(report,null,2));await browser.close();server.close();
  }
})().catch(error=>{console.error(error);server.close();process.exitCode=1;});

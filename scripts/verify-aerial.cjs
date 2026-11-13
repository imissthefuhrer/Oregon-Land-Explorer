// Validate the actual self-contained PR #2 preview with external networking blocked.
const {chromium}=require('playwright');
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
  const context=await browser.newContext({viewport:{width:1200,height:800}});
  await context.route(/^https?:/,route=>route.abort());
  const page=await context.newPage();
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  const source=fs.readFileSync(process.argv[2],'utf8').replace('L.control.layers({','window.__test={map,layer,aerial,parcels}; L.control.layers({');
  const tmp=path.resolve('work/offline-preview-test.html');fs.mkdirSync('work',{recursive:true});fs.writeFileSync(tmp,source);
  try {
    await page.goto('file://'+tmp);
    await page.waitForFunction(()=>document.querySelector('#imagery-status').dataset.state==='ready');
    assert.deepEqual(errors,[]);
    const expected=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));
    assert.deepEqual(await page.evaluate(()=>window.__test.parcels),expected);
    assert(await page.locator('.leaflet-image-layer').evaluate(i=>i.complete&&i.naturalWidth>256&&i.src.startsWith('data:image/jpeg;base64,')));
    for(let i=0;i<expected.features.length;i++) {
      await page.evaluate(i=>window.__test.layer.getLayers()[i].openPopup(),i);
      const text=await page.locator('.leaflet-popup-content').innerText();
      for(const k of ['TAXLOT','OWNERNAME','ACRES']) assert(text.includes(String(expected.features[i].properties[k])));
    }
    const point=await page.evaluate(()=>{ const {map,layer}=window.__test;map.closePopup();return map.latLngToContainerPoint(layer.getLayers()[0].getCenter()); });
    await page.mouse.click(point.x,point.y);
    await page.locator('.leaflet-popup-content').waitFor();
    await page.screenshot({path:'work/aerial-preview.png'});
    fs.writeFileSync('work/aerial-verification.json',JSON.stringify({result:'PASS',parcelsAndPopups:expected.features.length,pointerClick:'PASS',offlineEmbeddedImagery:'PASS',externalRequests:'blocked'},null,2));
  } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});

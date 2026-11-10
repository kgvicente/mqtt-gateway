// Optional browser checks: npm install --prefix target/browser playwright@1.55.1
const {chromium} = require('../target/browser/node_modules/playwright');
const assert = require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 const errors=[];
 const context=await browser.newContext({viewport:{width:1440,height:1100}});
 const page=await context.newPage();
 page.on('pageerror',e=>errors.push(e.message));
 const base='http://127.0.0.1:18085';
 async function authenticated(){
  await page.locator('#workspace').waitFor({state:'visible'});
  await page.locator('.card').first().waitFor();
  assert.equal(await page.locator('.card').count(),3);
 }
 try{
  await page.goto(base+'/gateway/');
  await page.locator('#login').waitFor({state:'visible'});
  // A token from a different origin must not authenticate the panel.
  await page.evaluate(()=>window.dispatchEvent(new MessageEvent('message',{origin:'https://wrong.example',source:window,data:{emqxToken:'smoke-token'}})));
  assert.equal(await page.locator('#workspace').isVisible(),false);
  await page.locator('#username').fill('admin');
  await page.locator('#password').fill('test-password');
  await page.locator('#login-form button').click();
  await authenticated();
  await page.screenshot({path:'target/admin-desktop.png',fullPage:true});
  // Register/edit/pause/enable/test/delete through the actual UI and API.
  const response=await page.request.get(base+'/api/admin/routes',{headers:{Authorization:'Bearer smoke-token'}});
  const initial=await response.json();
  const origin=new URL(initial[0].authUrl).origin;
  await page.locator('#register').click();
  await page.locator('[name=name]').fill('Browser backend');
  await page.locator('[name=authUrl]').fill(origin+'/browser/auth');
  await page.locator('[name=aclUrl]').fill(origin+'/browser/acl');
  await page.locator('[name=healthUrl]').fill(origin+'/health');
  await page.locator('[name=authPrefixes]').fill('browser_');
  await page.locator('[name=aclPrefixes]').fill('browser/');
  await page.locator('#route-form [type=submit]').click();
  const card=()=>page.locator('.card').filter({has:page.getByRole('heading',{name:'Browser backend',exact:true})});
  await card().waitFor();
  await card().locator('[data-action=edit]').click();
  await page.locator('[name=name]').fill('Browser edited');
  await page.locator('#route-form [type=submit]').click();
  const edited=page.locator('.card').filter({has:page.getByRole('heading',{name:'Browser edited',exact:true})});
  await edited.waitFor();
  await edited.locator('[data-action=pause]').click();
  await edited.getByText('Paused',{exact:true}).waitFor();
  await edited.locator('[data-action=pause]').click();
  await edited.locator('[data-action=test]:not([disabled])').waitFor();
  await edited.locator('[data-action=test]').click();
  await page.locator('#probe-toast').waitFor({state:'visible'});
  await edited.locator('[data-action=delete]').click();
  await page.locator('#delete-confirm-password').fill('test-password');
  await page.locator('#confirm-delete').click();
  await edited.waitFor({state:'detached'});
  for(const tab of ['incidents','logs','backends']){
   await page.locator('[data-tab='+tab+']').click();
   assert.equal(await page.locator('#'+tab).isVisible(),true);
  }
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:'target/admin-mobile.png',fullPage:true});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),'mobile overflow');
  await page.locator('#logout').click();
  // Shared EMQX token in localStorage.
  await page.evaluate(()=>localStorage.setItem('token','smoke-token'));
  await page.reload();await authenticated();
  await page.evaluate(()=>{localStorage.removeItem('token');sessionStorage.clear();sessionStorage.setItem('token','smoke-token')});
  await page.reload();await authenticated();
  // URL fragment is consumed and removed without sending the token to the server.
  await page.evaluate(()=>sessionStorage.clear());
  await page.goto(base+'/q/health');
  await page.goto(base+'/gateway/#token=smoke-token');await authenticated();
  assert.ok(!page.url().includes('token='));
  await page.evaluate(()=>{localStorage.clear();sessionStorage.clear()});
  // Actual same-origin parent/iframe exchange.
  await page.route('**/gateway/frame-test',route=>route.fulfill({contentType:'text/html',body:'<!doctype html><iframe src="/gateway/"></iframe><script>addEventListener("message",e=>{if(e.origin===location.origin&&(e.data.type==="mqtt-gateway:request-token"||e.data.type==="emqx-gateway:request-token"))e.source.postMessage({token:"smoke-token"},location.origin)})</script>'}));
  await page.goto(base+'/gateway/frame-test');
  const frame=page.frameLocator('iframe');
  await frame.locator('#workspace').waitFor({state:'visible'});
  await frame.locator('.card').first().waitFor();
  assert.deepEqual(errors,[]);
  console.log('PASS: browser login, local/session storage SSO, fragment cleanup, iframe postMessage/origin guard, CRUD/pause/test/delete, tabs, responsive viewport; no JS errors');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});

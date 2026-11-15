// Optional browser checks: npm install --prefix target/browser playwright@1.55.1
const {chromium} = require('../target/browser/node_modules/playwright');
const assert = require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 const errors=[];
 const context=await browser.newContext({viewport:{width:1440,height:1100}});
 const page=await context.newPage();
 page.on('pageerror',e=>errors.push(e.message));
 const base=process.env.GATEWAY_BASE_URL || ('http://127.0.0.1:' + (process.env.GATEWAY_SMOKE_HTTP_PORT || '18085'));
 async function authenticated(){
  await page.locator('#workspace').waitFor({state:'visible'});
  await page.locator('[data-tab=backends]').click();
  await page.locator('#cards .card').first().waitFor({state:'visible'});
  assert.equal(await page.locator('#cards .card').count(),3);
 }
 try{
  await page.goto(base+'/gateway/');
  await page.locator('#login').waitFor({state:'visible'});
  // A token from a different origin must not authenticate the panel.
  await page.evaluate(()=>window.dispatchEvent(new MessageEvent('message',{origin:'https://wrong.example',source:window,data:{emqxToken:'smoke-token'}})));
  assert.equal(await page.locator('#workspace').isVisible(),false);
  await page.locator('#username').fill('admin');
  await page.locator('#password').fill('test-password');
  await page.locator('#login-form button[type=submit]').click();
  await authenticated();
  await page.screenshot({path:'target/admin-desktop.png',fullPage:true});
  // Settings globales de monitoreo y Telegram: verificar SOLO sección modificada en PUT y no-op sin PUT
  const settingsBtn = page.locator('#open-settings-btn');
  assert.ok(await settingsBtn.isVisible(), 'Botón de ajustes globales #open-settings-btn debe ser visible');

  const putSettingsBodies = [];
  await page.route('**/api/admin/settings', async (route, req) => {
    if (req.method() === 'PUT') {
      putSettingsBodies.push(req.postDataJSON());
    }
    await route.continue();
  });

  // 1. No-op sin PUT: abrir y guardar sin cambiar nada no debe enviar PUT
  await settingsBtn.click();
  const settingsDialog = page.locator('#settings-dialog');
  await settingsDialog.waitFor({state:'visible'});
  await page.locator('#settings-form button[type=submit]').click();
  await settingsDialog.waitFor({state:'hidden'});
  assert.equal(putSettingsBodies.length, 0, 'No debe emitir PUT /settings si no hubo cambios');

  // 2. SOLO sección modificada: editar intervalo de monitoreo debe enviar SOLO {monitoring}
  await settingsBtn.click();
  await settingsDialog.waitFor({state:'visible'});
  const intervalInput = page.locator('#settings-form [name=probeIntervalSeconds]');
  const curInterval = await intervalInput.inputValue();
  const newInterval = String(Number(curInterval) + 1);
  await intervalInput.fill(newInterval);
  await page.locator('#settings-form button[type=submit]').click();
  await settingsDialog.waitFor({state:'hidden'});
  assert.equal(putSettingsBodies.length, 1, 'Debe emitir 1 PUT al modificar monitoreo');
  assert.ok(putSettingsBodies[0].monitoring, 'El PUT debe incluir sección monitoring');
  assert.equal(putSettingsBodies[0].monitoring.probeIntervalSeconds, Number(newInterval));
  assert.equal(putSettingsBodies[0].telegram, undefined, 'El PUT NO debe incluir sección telegram omitida');

  // Restaurar intervalo original
  await settingsBtn.click();
  await settingsDialog.waitFor({state:'visible'});
  await intervalInput.fill(curInterval);
  await page.locator('#settings-form button[type=submit]').click();
  await settingsDialog.waitFor({state:'hidden'});

  // 3. Caso token ausente con Telegram guardado enabled=true: validar que monitoring se puede editar
  await page.evaluate(() => {
    globalSettings.telegram = { enabled: true, chatId: "-100123456789", tokenConfigured: false };
  });

  await page.route('**/api/admin/settings', async (route, req) => {
    if (req.method() === 'PUT') {
      putSettingsBodies.push(req.postDataJSON());
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          monitoring: req.postDataJSON().monitoring || { probeIntervalSeconds: 30, probeTimeoutMs: 2000, failureThreshold: 3, recoveryThreshold: 2 },
          telegram: { enabled: true, chatId: "-100123456789", tokenConfigured: false }
        })
      });
    } else {
      await route.continue();
    }
  });

  await settingsBtn.click();
  await settingsDialog.waitFor({state:'visible'});
  const intervalVal = Number(await page.locator('#settings-form [name=probeIntervalSeconds]').inputValue());
  const timeoutInput = page.locator('#settings-form [name=probeTimeoutMs]');
  const curTimeout = Number(await timeoutInput.inputValue());
  const maxTimeoutMs = intervalVal * 1000;
  const newTimeout = (curTimeout === 500) ? 600 : (curTimeout + 100 < maxTimeoutMs ? curTimeout + 100 : curTimeout - 100);
  assert.ok(newTimeout < maxTimeoutMs, 'El nuevo timeout de prueba debe ser menor al intervalo');
  await timeoutInput.fill(String(newTimeout));
  await page.locator('#settings-form button[type=submit]').click();
  try {
    await settingsDialog.waitFor({state:'hidden', timeout: 5000});
  } catch (err) {
    const formErr = await page.locator('#settings-form-error').textContent();
    throw new Error('Error al cerrar settings dialog: ' + formErr + ' (' + err.message + ')');
  }
  assert.ok(putSettingsBodies.some(b => b.monitoring && b.monitoring.probeTimeoutMs === newTimeout), 'Debe permitir editar monitoring aun cuando token de Telegram no esté configurado');

  // Restaurar timeout original
  await settingsBtn.click();
  await settingsDialog.waitFor({state:'visible'});
  await timeoutInput.fill(String(curTimeout));
  await page.locator('#settings-form button[type=submit]').click();
  await settingsDialog.waitFor({state:'hidden'});

  // Verificar que el input de token de Telegram se limpia al cancelar/Escape y reabre vacío
  await settingsBtn.click();
  await settingsDialog.waitFor({state:'visible'});
  const botTokenInput = page.locator('#settings-telegram-bot-token');
  assert.equal(await botTokenInput.inputValue(), '', 'Input de token en settings debe estar vacío al abrir');
  await botTokenInput.fill('unsaved-telegram-token-to-discard');
  await page.keyboard.press('Escape');
  await settingsDialog.waitFor({state:'hidden'});
  await settingsBtn.click();
  await settingsDialog.waitFor({state:'visible'});
  assert.equal(await botTokenInput.inputValue(), '', 'Input de token en settings debe estar vacío tras cancelar');
  await page.locator('#close-settings-dialog').click();
  await settingsDialog.waitFor({state:'hidden'});

  // Restaurar globalSettings inicial
  await page.evaluate(() => {
    if (globalSettings) {
      globalSettings.telegram = { enabled: false, chatId: null, tokenConfigured: false };
    }
  });
  await page.unroute('**/api/admin/settings');
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
  const apiKeyInput = page.locator('[name=apiKey]');
  assert.equal(await apiKeyInput.isVisible(), true, 'Campo [name=apiKey] debe ser visible para asignar clave opcional');
  await apiKeyInput.fill('test-browser-api-key');
  await page.locator('#route-form [type=submit]').click();
  const card=()=>page.locator('#cards .card').filter({has:page.getByRole('heading',{name:'Browser backend',exact:true})});
  await card().waitFor();

  // Verificar que al editar, el campo de clave secreta siempre está vacío por seguridad
  await card().locator('[data-action=edit]').click();
  await page.locator('#route-dialog').waitFor({state:'visible'});
  assert.equal(await apiKeyInput.inputValue(), '', 'Campo de clave en edición debe estar siempre vacío');
  assert.equal(await page.locator('#clear-key-label').isVisible(), true, 'Opción de eliminar clave debe ser visible en backend con clave');
  // Editar solo el nombre sin tocar la clave -> conserva el secreto
  await page.locator('[name=name]').fill('Browser edited');
  await page.locator('#route-form [type=submit]').click();
  await page.locator('#route-dialog').waitFor({state:'hidden'});

  // Verificar que cancelar o presionar Escape limpia inputs de secretos y al reabrir están vacíos
  const edited=page.locator('#cards .card').filter({has:page.getByRole('heading',{name:'Browser edited',exact:true})});
  await edited.locator('[data-action=edit]').click();
  await page.locator('#route-dialog').waitFor({state:'visible'});
  await apiKeyInput.fill('unsaved-secret-to-discard');
  await page.keyboard.press('Escape');
  await page.locator('#route-dialog').waitFor({state:'hidden'});
  await edited.locator('[data-action=edit]').click();
  await page.locator('#route-dialog').waitFor({state:'visible'});
  assert.equal(await apiKeyInput.inputValue(), '', 'Input de clave debe estar vacío tras haber cancelado');
  await page.locator('#close-dialog').click();
  await page.locator('#route-dialog').waitFor({state:'hidden'});

  // Verificar que ni localStorage ni sessionStorage almacenan secretos
  const clientStorages = await page.evaluate(() => JSON.stringify({ local: localStorage, session: sessionStorage }));
  assert.ok(!clientStorages.includes('test-browser-api-key'), 'localStorage/sessionStorage no debe contener la clave secreta');
  assert.ok(!clientStorages.includes('unsaved-secret-to-discard'), 'localStorage/sessionStorage no debe contener secretos descartados');

  await edited.waitFor();
  await edited.locator('[data-action=pause]').click();
  await edited.getByText('Paused',{exact:true}).waitFor();

  // Verificaciones de Fase 3: Notificaciones Telegram en UI
  const notifTab = page.locator('[data-tab=notifications]');
  assert.ok(await notifTab.isVisible(), 'Pestaña de notificaciones [data-tab=notifications] debe existir y ser visible');

  // Configurar estado en browser y aislar transporte SSE para que no resetee el fixture
  await page.evaluate(() => {
    if (typeof sseSource !== 'undefined' && sseSource) {
      sseSource.close();
      sseSource = null;
    }
    if (typeof globalSettings !== 'undefined') {
      globalSettings = {
        monitoring: { probeIntervalSeconds: 30, probeTimeoutMs: 2000, failureThreshold: 3, recoveryThreshold: 2 },
        telegram: { enabled: true, chatId: '-100123456789', tokenConfigured: true }
      };
    }
  });
  await page.route('**/api/admin/settings', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      monitoring: { probeIntervalSeconds: 30, probeTimeoutMs: 2000, failureThreshold: 3, recoveryThreshold: 2 },
      telegram: { enabled: true, chatId: '-100123456789', tokenConfigured: true }
    })
  }));

  await notifTab.click();
  await page.locator('#notifications').waitFor({state:'visible'});
  await page.screenshot({path:'target/admin-notifications-desktop.png',fullPage:true});

  // 1. Verificar que ruta pausada aparece deshabilitada en el modal de prueba
  const openTestBtn = page.locator('#open-test-notification');
  await openTestBtn.click();
  const testDialog = page.locator('#test-notification-dialog');
  await testDialog.waitFor({state:'visible'});

  const pausedOpt = page.locator('#test-notif-target option').filter({hasText:'Browser edited'});
  assert.ok((await pausedOpt.count()) >= 1, 'Debe haber opción para la ruta pausada en el selector de prueba');
  assert.equal(await pausedOpt.first().getAttribute('disabled'), '', 'Ruta pausada debe estar deshabilitada en prueba');
  const optText = await pausedOpt.first().textContent();
  assert.ok(optText.includes('[Pausado]'), 'Texto de opción de ruta pausada debe indicar [Pausado]');

  const submitTestBtn = page.locator('#submit-test-notif');
  assert.equal(await submitTestBtn.isEnabled(), true, 'Botón de submit debe estar habilitado cuando token y destino están listos');

  // Seleccionar deliberadamente destino global (value='') sin alterar validación DOM
  await page.locator('#test-notif-target').selectOption('');

  // 2. Mock 202 status=pending -> Validar payload vacío {} y Toast "Encolada" con ID
  let sentPayload = null;
  await page.route('**/api/admin/notifications/test', async (route, req) => {
    sentPayload = req.postDataJSON();
    await route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'ui-test-pending-1', status: 'pending' })
    });
  });
  await submitTestBtn.click();
  await testDialog.waitFor({state:'hidden'});
  assert.deepEqual(sentPayload, {}, 'Envío con destino global seleccionado debe mandar payload vacío {}');

  const toastPending = page.locator('#probe-toast');
  await toastPending.waitFor({state:'visible'});
  const pendingText = await toastPending.textContent();
  assert.ok(pendingText.includes('encolada') || pendingText.includes('Encolada'), 'Toast debe indicar encolada para status pending');
  assert.ok(pendingText.includes('ui-test-pending-1'), 'Toast debe incluir el ID de la notificación');
  await toastPending.waitFor({state:'hidden'});

  // 3. Mock 202 status=not_enqueued -> Validar Toast "Cola llena" con ID
  await page.route('**/api/admin/notifications/test', async (route, req) => {
    sentPayload = req.postDataJSON();
    await route.fulfill({
      status: 202,
      contentType: 'application/json',
      body: JSON.stringify({ id: 'ui-test-sat-2', status: 'not_enqueued' })
    });
  });
  await openTestBtn.click();
  await testDialog.waitFor({state:'visible'});
  assert.equal(await submitTestBtn.isEnabled(), true, 'Botón de submit debe estar habilitado');
  await page.locator('#test-notif-target').selectOption('');
  await submitTestBtn.click();
  await testDialog.waitFor({state:'hidden'});
  assert.deepEqual(sentPayload, {}, 'Envío con destino global debe mandar payload vacío {}');
  const toastSat = page.locator('#probe-toast');
  await toastSat.waitFor({state:'visible'});
  const satText = await toastSat.textContent();
  assert.ok(satText.includes('cola llena') || satText.includes('Cola llena'), 'Toast debe indicar cola llena para status not_enqueued');
  assert.ok(satText.includes('ui-test-sat-2'), 'Toast debe incluir el ID de la notificación rechazada por cola llena');
  await page.unroute('**/api/admin/notifications/test');

  // Probar que sin token configurado el botón de submit permanece disabled
  await page.evaluate(() => {
    if (typeof globalSettings !== 'undefined' && globalSettings) {
      globalSettings.telegram = { enabled: true, chatId: '-100123456789', tokenConfigured: false };
    }
  });
  await openTestBtn.click();
  await testDialog.waitFor({state:'visible'});
  assert.equal(await submitTestBtn.isEnabled(), false, 'Botón de submit debe estar disabled si tokenConfigured es false');
  await page.locator('#close-test-notif-dialog').click();
  await testDialog.waitFor({state:'hidden'});

  // Restaurar tokenConfigured=true para pruebas siguientes
  await page.evaluate(() => {
    if (typeof globalSettings !== 'undefined' && globalSettings) {
      globalSettings.telegram = { enabled: true, chatId: '-100123456789', tokenConfigured: true };
    }
  });

  // 4. Paginación de historial con mock dinámico por offset (25 items p1, 10 items p2)
  await page.route('**/api/admin/notifications*', route => {
    const url = new URL(route.request().url());
    const offset = Number(url.searchParams.get('offset') || 0);
    const count = offset === 0 ? 25 : 10;
    const startIdx = offset;
    const items = Array(count).fill(null).map((_, i) => ({
      id: 'mock-notif-' + (startIdx + i),
      backendId: null,
      incidentId: null,
      type: 'test',
      chatId: '-100123456789',
      status: 'sent',
      attempts: 1,
      createdAt: '2026-11-14T10:00:00Z',
      nextAttemptAt: '2026-11-14T10:00:00Z',
      sentAt: '2026-11-14T10:00:01Z',
      lastError: null
    }));
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: items,
        total: 35,
        limit: 25,
        offset: offset,
        summary: { pending: 0, sending: 0, sent: 35, failed: 0, expired: 0, cancelled: 0, notEnqueued: 0, oldestPendingAt: null }
      })
    });
  });

  await Promise.all([
    page.waitForResponse(resp => resp.url().includes('/notifications') && resp.status() === 200),
    page.locator('#refresh-notifications').click()
  ]);
  const prevBtn = page.locator('#notif-prev-page');
  const nextBtn = page.locator('#notif-next-page');

  // Página 1: offset=0, 25 items renderizados
  assert.ok(await prevBtn.getAttribute('disabled') !== null, 'Botón Anterior debe estar deshabilitado en página 1');
  assert.ok(await nextBtn.getAttribute('disabled') === null, 'Botón Siguiente debe estar habilitado en página 1');
  assert.equal(await page.locator('#notification-rows tr').count(), 25, 'Página 1 debe renderizar 25 filas');

  // Clic en Siguiente -> Avanza a página 2 (offset=25, 10 items)
  await Promise.all([
    page.waitForResponse(resp => resp.url().includes('offset=25')),
    nextBtn.click()
  ]);
  assert.ok(await prevBtn.getAttribute('disabled') === null, 'Botón Anterior debe estar habilitado en página 2');
  assert.ok(await nextBtn.getAttribute('disabled') !== null, 'Botón Siguiente debe estar deshabilitado en página 2');
  assert.equal(await page.locator('#notification-rows tr').count(), 10, 'Página 2 debe renderizar los 10 items restantes');

  // Clic en Anterior -> Regresa a página 1 (offset=0, 25 items)
  await Promise.all([
    page.waitForResponse(resp => resp.url().includes('offset=0')),
    prevBtn.click()
  ]);
  assert.equal(await page.locator('#notification-rows tr').count(), 25, 'Página 1 debe volver a renderizar 25 filas');

  // Desactivar mocks y volver a la pestaña backends
  await page.evaluate(() => {
    if (typeof globalSettings !== 'undefined' && globalSettings) {
      globalSettings.telegram = { enabled: false, chatId: null, tokenConfigured: false };
    }
    if (typeof connectStream === 'function') {
      connectStream();
    }
  });
  await page.unroute('**/api/admin/notifications*');
  await page.unroute('**/api/admin/settings');
  await page.locator('[data-tab=backends]').click();

  await edited.locator('[data-action=pause]').click();
  await edited.locator('[data-action=test]:not([disabled])').waitFor();
  await edited.locator('[data-action=test]').click();
  await page.locator('#probe-toast').waitFor({state:'visible'});
  await edited.locator('[data-action=delete]').click();
  await page.locator('#delete-confirm-password').fill('test-password');
  await page.locator('#confirm-delete').click();
  await edited.waitFor({state:'detached'});
  for(const tab of ['incidents','logs','notifications','backends']){
   await page.locator('[data-tab='+tab+']').click();
   assert.equal(await page.locator('#'+tab).isVisible(),true);
  }
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:'target/admin-mobile.png',fullPage:true});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),'mobile overflow');
  await page.locator('[data-tab=notifications]').click();
  await page.screenshot({path:'target/admin-notifications-mobile.png',fullPage:true});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),'mobile notifications overflow');
  await page.locator('[data-tab=backends]').click();
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
  await frame.locator('[data-tab=backends]').click();
  await frame.locator('#cards .card').first().waitFor({state:'visible'});
  assert.deepEqual(errors,[]);
  console.log('PASS: browser login, local/session storage SSO, fragment cleanup, iframe postMessage/origin guard, CRUD/pause/test/delete, tabs, responsive viewport; no JS errors');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});

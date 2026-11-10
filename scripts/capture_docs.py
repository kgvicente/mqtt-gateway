"""Capturas reproducibles de la UI real con datos ilustrativos, sin servicios externos."""
import asyncio
import threading
from datetime import datetime, timedelta, timezone
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from playwright.async_api import async_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src/main/resources/META-INF/resources/gateway"
IMAGES = ROOT / "docs/images"


class StaticFiles(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


async def main():
    now = datetime.now(timezone.utc)
    def stamp(seconds):
        return (now - timedelta(seconds=seconds)).isoformat()

    routes = [dict(id=key, name=name, enabled=True, authPrefixes=[prefix],
                   aclPrefixes=[topic], authUrl=f"http://127.0.0.1:{port}/auth",
                   aclUrl=f"http://127.0.0.1:{port}/acl", healthUrl=f"http://127.0.0.1:{port}/health",
                   hasApiKey=True)
              for key, name, prefix, topic, port in [
                  ("core", "IoT Core Service", "core_", "core/", 19090),
                  ("device", "Device Gateway", "device_", "devices/", 19091),
                  ("telemetry", "Telemetry Service", "telemetry_", "telemetry/", 19092)]]
    metrics = []
    for i, route in enumerate(routes):
        samples = [round(24 + (n % 8) * 2.3 + i * 260, 1) for n in range(48)]
        metrics.append(dict(id=route["id"], name=route["name"], status=["Normal", "Degraded", "Offline"][i],
                            count=[1240, 318, 86][i], currentMs=samples[-1], averageMs=sum(samples)/48,
                            p95Ms=max(samples), minMs=min(samples), maxMs=max(samples),
                            uptimePercent=[99.8, 98.6, 91.2][i], probeMs=samples[-1],
                            probeHttpStatus=503 if i==2 else 200, lastProbe=stamp(8),
                            recentSamples=samples,
                            probeHistory=[dict(time=stamp((47-n)*30+8), online=not(i==2 and n>40),
                                               status=503 if i==2 and n>40 else 200, latencyMs=ms)
                                          for n, ms in enumerate(samples)]))
    incidents = [dict(id="demo-incident",backendId="telemetry",backend="Telemetry Service",
                      startedAt=stamp(180),endedAt=None,durationMs=180000,reason="HTTP 503")]
    logs = [dict(timestamp=stamp(n*18), type="acl" if n%2 else "auth", client=f"core_sensor_{n:02}",
                 topic="core/temperature" if n%2 else "", result="deny" if n==3 else "allow",
                 backend="IoT Core Service",latencyMs=24+n*1.5) for n in range(14)]
    server = ThreadingHTTPServer(("127.0.0.1",0),partial(StaticFiles,directory=str(WEB)))
    threading.Thread(target=server.serve_forever,daemon=True).start()
    IMAGES.mkdir(parents=True,exist_ok=True)
    try:
        async with async_playwright() as p:
            browser = await p.chromium.launch()
            for mobile in (False,True):
                context = await browser.new_context(viewport=dict(width=390 if mobile else 1440,height=844 if mobile else 940),
                                                    reduced_motion="reduce",device_scale_factor=1)
                await context.add_init_script("sessionStorage.setItem('gateway_emqx_token','docs-demo-only');localStorage.setItem('gateway_theme','dark')")
                page = await context.new_page()
                errors = []
                page.on("pageerror",lambda error: errors.append(str(error)))
                async def mock(route):
                    path = route.request.url.split("/api/admin")[-1]
                    if path.startswith("/stream"):
                        return await route.fulfill(status=200,content_type="text/event-stream",body=": demo\n\n")
                    data = {"/auth/verify":{"valid":True},"/routes":routes,"/metrics":metrics,
                            "/incidents":incidents,"/logs":logs}
                    assert path in data,path
                    await route.fulfill(status=200,json=data[path])
                await page.route("**/api/admin/**",mock)
                await page.goto(f"http://127.0.0.1:{server.server_port}/index.html")
                await expect(page.locator(".card")).to_have_count(3)
                assert await page.evaluate("document.documentElement.scrollWidth <= innerWidth")
                await page.screenshot(path=str(IMAGES/("mobile.png" if mobile else "dashboard.png")),full_page=True)
                if not mobile:
                    await page.locator('[data-action="detail"][data-id="core"]').first.click()
                    await page.wait_for_function("typeof Chart !== 'undefined' && Chart.getChart('duration-chart')")
                    await page.locator("#duration-chart").scroll_into_view_if_needed()
                    # ponytail: fijar el dibujo del canvas evita capturar un estado intermedio.
                    await page.evaluate("Chart.getChart('duration-chart').stop();Chart.getChart('duration-chart').update('none')")
                    await page.screenshot(path=str(IMAGES/"backend-detail.png"),full_page=True)
                assert not errors,errors
                await context.close()
            await browser.close()
        print("CORRECTO: tres capturas de la UI real con datos ilustrativos y sin errores JavaScript.")
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    asyncio.run(main())

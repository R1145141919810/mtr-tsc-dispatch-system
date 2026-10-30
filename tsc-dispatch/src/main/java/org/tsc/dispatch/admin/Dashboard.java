package org.tsc.dispatch.admin;

/** Self contained dashboard: no build step, no external assets, polls the JSON API directly. */
public final class Dashboard {

	private Dashboard() {
	}

	public static final String HTML = """
		<!doctype html>
		<html lang="zh">
		<head>
		<meta charset="utf-8">
		<title>TSC 动态调度服务</title>
		<style>
		:root { color-scheme: light dark; }
		body { font: 14px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; margin: 24px; background: #0f1116; color: #e8eaf0; }
		h1 { font-size: 18px; margin: 0 0 4px; font-weight: 600; letter-spacing: .2px; }
		.sub { color: #8b93a7; margin-bottom: 20px; }
		.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; margin-bottom: 20px; }
		.card { background: #171a22; border: 1px solid #262b38; border-radius: 10px; padding: 12px 14px; }
		.card .k { color: #8b93a7; font-size: 12px; text-transform: uppercase; letter-spacing: .6px; }
		.card .v { font-size: 22px; font-weight: 600; margin-top: 2px; }
		table { width: 100%; border-collapse: collapse; margin-top: 8px; }
		th, td { text-align: left; padding: 7px 10px; border-bottom: 1px solid #22262f; font-size: 13px; }
		th { color: #8b93a7; font-weight: 500; }
		.ok { color: #4ade80; } .warn { color: #fbbf24; } .bad { color: #f87171; }
		h2 { font-size: 14px; margin: 22px 0 0; font-weight: 600; }
		pre { background: #171a22; border: 1px solid #262b38; border-radius: 10px; padding: 12px; overflow: auto; font-size: 12px; }
		</style>
		</head>
		<body>
		<h1>TSC 动态调度服务</h1>
		<div class="sub" id="sub">loading…</div>
		<div class="grid" id="cards"></div>
		<h2>最近决策</h2>
		<table><thead><tr><th>时间</th><th>类型</th><th>列车</th><th>状态</th><th>说明</th></tr></thead><tbody id="decisions"></tbody></table>
		<h2>列车</h2>
		<table><thead><tr><th>ID</th><th>线路</th><th>站台</th><th>ETA</th><th>偏差</th><th>可控制</th></tr></thead><tbody id="vehicles"></tbody></table>
		<h2>原始状态</h2>
		<pre id="raw"></pre>
		<script>
		const fmt = (v) => v === null || v === undefined ? '-' : String(v);
		const ts = (v) => v ? new Date(v).toLocaleTimeString() : '-';
		async function refresh() {
		  try {
		    const status = await (await fetch('/v1/status')).json();
		    const world = status.world || {};
		    const health = status.health || {};
		    const healthy = health.state === 'OK';
		    document.getElementById('sub').innerHTML = '健康 <b class="' + (healthy ? 'ok' : 'warn') + '">' + fmt(health.state) +
		      '</b> · 数据源 ' + fmt(world.source) + ' · 车辆遥测 ' + (world.vehicleTelemetry ? '是' : '否') + ' · 上次轮询 ' + ts(health.lastSuccessAt);
		    const cards = [['列车', world.vehicles], ['可控列车', world.controllableVehicles], ['站台', world.platforms],
		      ['线路', world.routes], ['车厂', world.depots], ['决策（累计）', (status.dispatch || {}).total], ['最近缓冲', (status.dispatch || {}).recentTotal]];
		    document.getElementById('cards').innerHTML = cards.map(([k,v]) => '<div class="card"><div class="k">'+k+'</div><div class="v">'+fmt(v)+'</div></div>').join('');
		    const decisions = await (await fetch('/v1/decisions?limit=25')).json();
		    document.getElementById('decisions').innerHTML = (decisions.decisions || []).map(d =>
		      '<tr><td>'+ts(d.at)+'</td><td>'+fmt(d.type)+'</td><td>'+fmt(d.vehicle)+'</td><td>'+fmt(d.status)+'</td><td>'+fmt(d.reason)+'</td></tr>').join('');
		    const vehicles = await (await fetch('/v1/vehicles')).json();
		    document.getElementById('vehicles').innerHTML = (vehicles.vehicles || []).map(v =>
		      '<tr><td>'+v.id+'</td><td>'+v.route+'</td><td>'+v.platform+'</td><td>'+ts(v.eta)+'</td><td>'+(v.deviation/1000).toFixed(0)+'s</td><td>'+(v.controllable?'是':'否')+'</td></tr>').join('');
		    document.getElementById('raw').textContent = JSON.stringify(status.dispatch, null, 2);
		  } catch (e) {
		    document.getElementById('sub').textContent = '无法连接服务: ' + e;
		  }
		}
		refresh();
		setInterval(refresh, 2000);
		</script>
		</body>
		</html>
		""";
}

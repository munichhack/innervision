package com.blindvision.planning.dashboard

import com.blindvision.planning.BuildingGrid
import com.blindvision.planning.BuildingLoader
import com.blindvision.planning.BuildingPos
import com.blindvision.planning.CellType
import com.blindvision.planning.MockBuilding
import com.blindvision.planning.Reachability
import com.blindvision.planning.Route
import com.blindvision.planning.RoutePlanner
import com.blindvision.planning.TransitionSegment
import com.blindvision.planning.WalkSegment
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Zero-dependency dashboard for the planning module. JDK-only
 * (`com.sun.net.httpserver`), so it lives OUTSIDE the Android source set and
 * wraps the real [RoutePlanner].
 *
 * Serves two buildings: the 3-floor mock, and (if data/floor_plan.csv exists)
 * the real floor-plan mask. Endpoints:
 *   /buildings          -> list of building ids + sizes
 *   /building?id=ID     -> full grid (rows) for one building
 *   /plan?id=ID&sf&sx&sy&tf&tx&ty  -> planned segments
 *   /sample?id=ID       -> auto-picked (start, farthest target) + planned segments
 *
 * Run:
 *   kotlinc app/src/main/java/com/blindvision/planning/ dashboard/DashboardServer.kt -include-runtime -d /tmp/dashboard.jar
 *   java -Xmx2g -cp /tmp/dashboard.jar com.blindvision.planning.dashboard.DashboardServerKt 8080
 */
private class Registered(val id: String, val label: String, val grid: BuildingGrid) {
    val planner = RoutePlanner(grid)
}

private val buildings = LinkedHashMap<String, Registered>()

fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 8080

    register("mock", "Mock 3-floor", MockBuilding.build())
    val csv = File("data/floor_plan.csv")
    if (csv.exists()) {
        println("Loading ${csv.path} ...")
        register("real", "Floor plan (data/floor_plan.csv)", loadCsvBuilding(csv))
    } else {
        println("(data/floor_plan.csv not found — only the mock building is available)")
    }

    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/buildings") { ex -> respond(ex, "application/json", buildingsJson()) }
    server.createContext("/building") { ex -> respond(ex, "application/json", buildingJson(pick(ex))) }
    server.createContext("/plan") { ex -> handlePlan(ex) }
    server.createContext("/sample") { ex -> handleSample(ex) }
    server.createContext("/") { ex -> respond(ex, "text/html; charset=utf-8", INDEX_HTML) }
    server.executor = null
    server.start()
    println("Dashboard running:  http://localhost:$port   (buildings: ${buildings.keys})")
    println("(Ctrl+C to stop)")
}

private fun register(id: String, label: String, grid: BuildingGrid) {
    buildings[id] = Registered(id, label, grid)
}

private fun loadCsvBuilding(file: File): BuildingGrid {
    val rows = file.readLines().filter { it.isNotBlank() }
    val h = rows.size
    val codes = Array(h) { y -> rows[y].split(",").map { it.trim().toInt() }.toIntArray() }
    val w = codes[0].size
    val floorXY = Array(w) { x -> IntArray(h) { y -> codes[y][x] } }
    return BuildingLoader.fromCodes(listOf(floorXY))
}

private fun pick(ex: HttpExchange): Registered {
    val id = query(ex)["id"]
    return buildings[id] ?: buildings.values.first()
}

// --- endpoints --------------------------------------------------------------

private fun handlePlan(ex: HttpExchange) {
    val b = pick(ex)
    val q = query(ex)
    fun i(k: String) = q[k]?.toIntOrNull()
    val sf = i("sf"); val sx = i("sx"); val sy = i("sy")
    val tf = i("tf"); val tx = i("tx"); val ty = i("ty")
    if (sf == null || sx == null || sy == null || tf == null || tx == null || ty == null) {
        respond(ex, "application/json", "{\"ok\":false,\"error\":\"missing params\"}")
        return
    }
    val route = b.planner.plan(BuildingPos(sf, sx, sy), BuildingPos(tf, tx, ty))
    respond(ex, "application/json", planJson(route, null, null))
}

private fun handleSample(ex: HttpExchange) {
    val b = pick(ex)
    val floor = b.grid.floors[0]
    val start = Reachability.firstWalkable(floor)
    if (start == null) { respond(ex, "application/json", "{\"ok\":false}"); return }
    val (target, _) = Reachability.farthestReachable(floor, start)
    val route = b.planner.plan(BuildingPos(0, start.x, start.y), BuildingPos(0, target.x, target.y))
    respond(ex, "application/json", planJson(route, BuildingPos(0, start.x, start.y), BuildingPos(0, target.x, target.y)))
}

// --- JSON -------------------------------------------------------------------

private fun buildingsJson(): String {
    val sb = StringBuilder("[")
    buildings.values.forEachIndexed { i, b ->
        if (i > 0) sb.append(",")
        val f0 = b.grid.floors[0]
        sb.append("{\"id\":\"").append(b.id).append("\",\"label\":\"").append(b.label)
            .append("\",\"floors\":").append(b.grid.floors.size)
            .append(",\"width\":").append(f0.width).append(",\"height\":").append(f0.height).append("}")
    }
    sb.append("]")
    return sb.toString()
}

private fun buildingJson(b: Registered): String {
    val sb = StringBuilder()
    sb.append("{\"id\":\"").append(b.id).append("\",\"floors\":[")
    b.grid.floors.forEachIndexed { fi, f ->
        if (fi > 0) sb.append(",")
        sb.append("{\"index\":").append(f.index)
            .append(",\"width\":").append(f.width)
            .append(",\"height\":").append(f.height).append(",\"rows\":[")
        for (y in 0 until f.height) {
            if (y > 0) sb.append(",")
            sb.append("\"")
            for (x in 0 until f.width) {
                sb.append(
                    when (f.typeAt(x, y)) {
                        CellType.WALKABLE -> '.'
                        CellType.NON_WALKABLE -> '#'
                        CellType.PORTAL -> 'E'
                    }
                )
            }
            sb.append("\"")
        }
        sb.append("]}")
    }
    sb.append("]}")
    return sb.toString()
}

private fun planJson(route: Route?, start: BuildingPos?, target: BuildingPos?): String {
    if (route == null) return "{\"ok\":false}"
    val sb = StringBuilder()
    sb.append("{\"ok\":true,\"walkCells\":").append(route.walkCells)
        .append(",\"rides\":").append(route.floorChanges)
    if (start != null) sb.append(",\"start\":{\"f\":").append(start.floor).append(",\"x\":").append(start.x).append(",\"y\":").append(start.y).append("}")
    if (target != null) sb.append(",\"target\":{\"f\":").append(target.floor).append(",\"x\":").append(target.x).append(",\"y\":").append(target.y).append("}")
    sb.append(",\"segments\":[")
    route.segments.forEachIndexed { i, s ->
        if (i > 0) sb.append(",")
        when (s) {
            is WalkSegment -> {
                sb.append("{\"type\":\"walk\",\"floor\":").append(s.floor).append(",\"path\":[")
                s.path.forEachIndexed { j, p ->
                    if (j > 0) sb.append(",")
                    sb.append("[").append(p.x).append(",").append(p.y).append("]")
                }
                sb.append("]}")
            }
            is TransitionSegment -> {
                sb.append("{\"type\":\"ride\",\"fromFloor\":").append(s.fromFloor)
                    .append(",\"toFloor\":").append(s.toFloor)
                    .append(",\"x\":").append(s.at.x)
                    .append(",\"y\":").append(s.at.y)
                    .append(",\"shaft\":").append(s.shaftId).append("}")
            }
        }
    }
    sb.append("]}")
    return sb.toString()
}

// --- http helpers -----------------------------------------------------------

private fun respond(ex: HttpExchange, contentType: String, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    ex.responseHeaders.add("Content-Type", contentType)
    ex.sendResponseHeaders(200, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

private fun query(ex: HttpExchange): Map<String, String> =
    ex.requestURI.rawQuery?.split("&")?.mapNotNull {
        val p = it.split("="); if (p.size == 2) p[0] to p[1] else null
    }?.toMap() ?: emptyMap()

// Embedded single-page UI. No '$' and no triple-quote inside, so it is a safe Kotlin raw string.
private val INDEX_HTML = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<title>BlindVision Planner</title>
<style>
 body{font-family:system-ui,Segoe UI,Roboto,sans-serif;margin:0;background:#0f141a;color:#e6edf3}
 header{padding:14px 20px;background:#161b22;border-bottom:1px solid #283038}
 h1{font-size:16px;margin:0}
 .controls{display:flex;gap:18px;align-items:flex-end;flex-wrap:wrap;padding:14px 20px;background:#11161c;border-bottom:1px solid #283038}
 .grp{display:flex;flex-direction:column;gap:4px}
 .grp > label{font-size:11px;color:#8b949e;text-transform:uppercase;letter-spacing:.04em}
 .row{display:flex;gap:6px}
 input{width:60px;background:#0d1117;border:1px solid #30363d;color:#e6edf3;border-radius:6px;padding:6px;font-size:13px}
 select{background:#0d1117;border:1px solid #30363d;color:#e6edf3;border-radius:6px;padding:7px;font-size:13px}
 button{background:#238636;color:#fff;border:0;border-radius:6px;padding:8px 16px;font-weight:600;cursor:pointer}
 button.sec{background:#30363d}
 button.alt{background:#1f6feb}
 #status{font-size:13px;color:#9aa4ae;margin-left:auto;max-width:480px}
 #floors{padding:20px;display:flex;flex-direction:column;gap:26px;overflow:auto}
 .floor h2{font-size:13px;color:#adbac7;margin:0 0 8px}
 canvas{background:#0d1117;border:1px solid #283038;border-radius:8px;cursor:crosshair;image-rendering:pixelated}
 .legend{font-size:12px;color:#8b949e;display:flex;gap:16px;padding:0 20px 4px;flex-wrap:wrap}
 .legend span{display:inline-flex;align-items:center;gap:6px}
 .sw{width:12px;height:12px;border-radius:3px;display:inline-block}
</style>
</head>
<body>
<header><h1>BlindVision &middot; Multi-floor Path Planner</h1></header>
<div class="controls">
  <div class="grp"><label>Building</label><select id="building" onchange="onBuildingChange()"></select></div>
  <div class="grp"><label>Start (floor, x, y)</label>
    <div class="row"><input id="sf" placeholder="f"><input id="sx" placeholder="x"><input id="sy" placeholder="y"></div></div>
  <div class="grp"><label>Target (floor, x, y)</label>
    <div class="row"><input id="tf" placeholder="f"><input id="tx" placeholder="x"><input id="ty" placeholder="y"></div></div>
  <button onclick="plan()">Plan route</button>
  <button class="alt" onclick="runSample()">Run sample test</button>
  <button class="sec" onclick="clearRoute()">Clear</button>
  <span id="status">Pick a building, click cells or type coordinates, then Plan. Or Run sample test.</span>
</div>
<div class="legend">
  <span><i class="sw" style="background:#e8edf2"></i>walkable</span>
  <span><i class="sw" style="background:#2b3440"></i>wall</span>
  <span><i class="sw" style="background:#6aa9ff"></i>portal</span>
  <span><i class="sw" style="background:#ff8c00"></i>walk path</span>
  <span><i class="sw" style="background:#b07cff"></i>ride</span>
  <span><i class="sw" style="background:#2ecc71"></i>start</span>
  <span><i class="sw" style="background:#e5484d"></i>target</span>
</div>
<div id="floors"></div>
<script>
var CS=26, building=null, segments=null, clickTarget=false, autoSampleDone=false;
var COL={wall:[43,52,64], portal:[106,169,255], walk:[232,237,242]};
function el(id){return document.getElementById(id);}
function val(id){var v=parseInt(el(id).value);return isNaN(v)?null:v;}
function curId(){return el('building').value;}

fetch('/buildings').then(function(r){return r.json();}).then(function(list){
  var sel=el('building'); sel.innerHTML='';
  list.forEach(function(b){
    var o=document.createElement('option'); o.value=b.id;
    o.textContent=b.label+' ['+b.width+'x'+b.height+', '+b.floors+' floor(s)]';
    sel.appendChild(o);
  });
  if(list.length){ sel.value=list[list.length-1].id; loadBuilding(true); }
});

function onBuildingChange(){ segments=null; clearFields(); loadBuilding(false); }

function loadBuilding(autoSample){
  fetch('/building?id='+encodeURIComponent(curId())).then(function(r){return r.json();}).then(function(b){
    building=b;
    var maxW=0; b.floors.forEach(function(f){ if(f.width>maxW) maxW=f.width; });
    CS=Math.max(1, Math.min(26, Math.floor(720/maxW)));
    renderFloors();
    if(autoSample && !autoSampleDone){ autoSampleDone=true; runSample(); }
  });
}

function renderFloors(){
  var host=el('floors'); host.innerHTML='';
  building.floors.forEach(function(f){
    var wrap=document.createElement('div'); wrap.className='floor';
    var h=document.createElement('h2'); h.textContent='Floor '+f.index+'  ('+f.width+' x '+f.height+', cell='+CS+'px)';
    var cv=document.createElement('canvas');
    cv.width=f.width*CS; cv.height=f.height*CS; cv.dataset.floor=f.index;
    cv.addEventListener('click', function(ev){onCellClick(ev,f);});
    wrap.appendChild(h); wrap.appendChild(cv); host.appendChild(wrap);
    drawFloor(f,cv);
  });
  if(segments) drawOverlay();
}
function floorCanvas(idx){
  var cvs=document.querySelectorAll('canvas');
  for(var i=0;i<cvs.length;i++){ if(parseInt(cvs[i].dataset.floor)===idx) return cvs[i]; }
  return null;
}
function colorOf(c){ return c==='#'?COL.wall:(c==='E'?COL.portal:COL.walk); }
function drawFloor(f,cv){
  var g=cv.getContext('2d');
  if(CS===1){
    var im=g.createImageData(f.width,f.height), d=im.data;
    for(var y=0;y<f.height;y++){ var row=f.rows[y];
      for(var x=0;x<f.width;x++){ var col=colorOf(row.charAt(x)); var o=(y*f.width+x)*4; d[o]=col[0];d[o+1]=col[1];d[o+2]=col[2];d[o+3]=255; } }
    g.putImageData(im,0,0);
  } else {
    g.clearRect(0,0,cv.width,cv.height);
    for(var y2=0;y2<f.height;y2++){ var row2=f.rows[y2];
      for(var x2=0;x2<f.width;x2++){ var col2=colorOf(row2.charAt(x2)); g.fillStyle='rgb('+col2[0]+','+col2[1]+','+col2[2]+')'; g.fillRect(x2*CS+1,y2*CS+1,CS-2,CS-2); } }
    g.fillStyle='#0d1117'; g.font='10px monospace'; g.textAlign='center';
    for(var y3=0;y3<f.height;y3++){ for(var x3=0;x3<f.width;x3++){ if(f.rows[y3].charAt(x3)==='E') g.fillText('E',x3*CS+CS/2,y3*CS+CS/2+3); } }
  }
}
function drawOverlay(){
  if(!segments) return;
  segments.forEach(function(s){
    if(s.type==='walk'){
      var cv=floorCanvas(s.floor); if(!cv) return; var g=cv.getContext('2d');
      g.strokeStyle='#ff8c00'; g.lineWidth=Math.max(2,CS*0.6); g.lineJoin='round'; g.lineCap='round'; g.beginPath();
      s.path.forEach(function(p,i){ var cx=p[0]*CS+CS/2, cy=p[1]*CS+CS/2; if(i===0) g.moveTo(cx,cy); else g.lineTo(cx,cy); });
      g.stroke();
    } else {
      var arrow = s.toFloor>s.fromFloor ? '^' : 'v';
      [s.fromFloor,s.toFloor].forEach(function(fl){
        var cv=floorCanvas(fl); if(!cv) return; var g=cv.getContext('2d');
        var cx=s.x*CS+CS/2, cy=s.y*CS+CS/2;
        g.fillStyle='#b07cff'; g.beginPath(); g.arc(cx,cy,Math.max(5,CS*0.34),0,7); g.fill();
        if(CS>=14){ g.fillStyle='#0d1117'; g.font='9px monospace'; g.textAlign='center'; g.fillText(s.fromFloor+arrow+s.toFloor,cx,cy+3); }
      });
    }
  });
  drawMarker(val('sf'),val('sx'),val('sy'),'#2ecc71');
  drawMarker(val('tf'),val('tx'),val('ty'),'#e5484d');
}
function drawMarker(f,x,y,color){
  if(f===null||x===null||y===null) return;
  var cv=floorCanvas(f); if(!cv) return; var g=cv.getContext('2d');
  var cx=x*CS+CS/2, cy=y*CS+CS/2;
  g.fillStyle=color; g.beginPath(); g.arc(cx,cy,Math.max(5,CS*0.30),0,7); g.fill();
  g.strokeStyle='#0d1117'; g.lineWidth=2; g.stroke();
}
function redraw(){ building.floors.forEach(function(f){ drawFloor(f,floorCanvas(f.index)); }); drawOverlay(); }
function onCellClick(ev,f){
  var rect=ev.target.getBoundingClientRect();
  var x=Math.floor((ev.clientX-rect.left)/CS), y=Math.floor((ev.clientY-rect.top)/CS);
  if(x<0||y<0||x>=f.width||y>=f.height) return;
  if(!clickTarget){ el('sf').value=f.index; el('sx').value=x; el('sy').value=y; el('status').textContent='Start = floor '+f.index+' ('+x+','+y+'). Click/enter target next.'; }
  else { el('tf').value=f.index; el('tx').value=x; el('ty').value=y; el('status').textContent='Target = floor '+f.index+' ('+x+','+y+'). Press Plan.'; }
  clickTarget=!clickTarget; redraw();
}
function plan(){
  var sf=val('sf'),sx=val('sx'),sy=val('sy'),tf=val('tf'),tx=val('tx'),ty=val('ty');
  if([sf,sx,sy,tf,tx,ty].some(function(v){return v===null;})){ el('status').textContent='Enter all six start/target coordinates.'; return; }
  var qs='/plan?id='+encodeURIComponent(curId())+'&sf='+sf+'&sx='+sx+'&sy='+sy+'&tf='+tf+'&tx='+tx+'&ty='+ty;
  el('status').textContent='Planning...';
  fetch(qs).then(function(r){return r.json();}).then(function(res){ applyResult(res); });
}
function runSample(){
  el('status').textContent='Running sample test...';
  fetch('/sample?id='+encodeURIComponent(curId())).then(function(r){return r.json();}).then(function(res){
    if(res.ok && res.start){ el('sf').value=res.start.f; el('sx').value=res.start.x; el('sy').value=res.start.y;
      el('tf').value=res.target.f; el('tx').value=res.target.x; el('ty').value=res.target.y; }
    applyResult(res, true);
  });
}
function applyResult(res, sample){
  if(!res.ok){ segments=null; redraw(); el('status').textContent='No route found.'; return; }
  segments=res.segments;
  el('status').textContent=(sample?'Sample test - ':'')+'Route: '+res.segments.length+' segment(s), '+res.walkCells+' walk cells, '+res.rides+' ride(s).';
  redraw();
}
function clearFields(){ ['sf','sx','sy','tf','tx','ty'].forEach(function(id){el(id).value='';}); clickTarget=false; }
function clearRoute(){ segments=null; clearFields(); el('status').textContent='Cleared.'; redraw(); }
</script>
</body>
</html>
""".trimIndent()

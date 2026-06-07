package com.blindvision.planning.dashboard

import com.blindvision.planning.AStarGridPlanner
import com.blindvision.planning.BuildingGrid
import com.blindvision.planning.BuildingLoader
import com.blindvision.planning.BuildingPos
import com.blindvision.planning.CellType
import com.blindvision.planning.MockBuilding
import com.blindvision.planning.Reachability
import com.blindvision.planning.Route
import com.blindvision.planning.RoutePlanner
import com.blindvision.planning.VoronoiGridPlanner
import com.blindvision.planning.TransitionSegment
import com.blindvision.planning.WalkSegment
import com.blindvision.planning.tools.DestinationResolver
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
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
private class Registered(
    val id: String,
    val label: String,
    val grid: BuildingGrid,
    val rooms: String? = null,
) {
    // One shared Voronoi planner so the skeleton is built once and reused by both
    // routing and the /voronoi boundary endpoint.
    val voronoi = VoronoiGridPlanner()

    // Cache a RoutePlanner per grid-planner kind so the heavy per-floor fields
    // (clearance / Voronoi skeleton) are built once and reused across requests.
    private val planners = HashMap<String, RoutePlanner>()

    fun planner(kind: String?): RoutePlanner = planners.getOrPut(kind ?: "astar") {
        when (kind) {
            "voronoi" -> RoutePlanner(grid, voronoi)
            else -> RoutePlanner(grid, AStarGridPlanner())
        }
    }
}

private val buildings = LinkedHashMap<String, Registered>()

fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 8080

    register("mock", "Mock 3-floor", MockBuilding.build())
    val csv = File("data/floor_plan.csv")
    if (csv.exists()) {
        println("Loading ${csv.path} ...")
        val roomsFile = File("data/message.json")
        val rooms = if (roomsFile.exists()) roomsFile.readText() else null
        if (rooms != null) println("Loaded room assignments from ${roomsFile.path}")
        register("real", "Floor plan (data/floor_plan.csv)", loadCsvBuilding(csv), rooms)
    } else {
        println("(data/floor_plan.csv not found — only the mock building is available)")
    }

    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/buildings") { ex -> respond(ex, "application/json", buildingsJson()) }
    server.createContext("/building") { ex -> respond(ex, "application/json", buildingJson(pick(ex))) }
    server.createContext("/plan") { ex -> handlePlan(ex) }
    server.createContext("/sample") { ex -> handleSample(ex) }
    server.createContext("/voronoi") { ex -> respond(ex, "application/json", voronoiJson(pick(ex))) }
    server.createContext("/rooms") { ex -> respond(ex, "application/json", pick(ex).rooms ?: "{\"items\":[]}") }
    server.createContext("/resolve") { ex -> handleResolve(ex) }
    server.createContext("/") { ex -> respond(ex, "text/html; charset=utf-8", INDEX_HTML) }
    server.executor = null
    server.start()
    println("Dashboard running:  http://localhost:$port   (buildings: ${buildings.keys})")
    println("(Ctrl+C to stop)")
}

private fun register(id: String, label: String, grid: BuildingGrid, rooms: String? = null) {
    buildings[id] = Registered(id, label, grid, rooms)
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
    val route = b.planner(q["planner"]).plan(BuildingPos(sf, sx, sy), BuildingPos(tf, tx, ty))
    respond(ex, "application/json", planJson(route, null, null))
}

private fun handleSample(ex: HttpExchange) {
    val b = pick(ex)
    val floor = b.grid.floors[0]
    val start = Reachability.firstWalkable(floor)
    if (start == null) { respond(ex, "application/json", "{\"ok\":false}"); return }
    val (target, _) = Reachability.farthestReachable(floor, start)
    val route = b.planner(query(ex)["planner"])
        .plan(BuildingPos(0, start.x, start.y), BuildingPos(0, target.x, target.y))
    respond(ex, "application/json", planJson(route, BuildingPos(0, start.x, start.y), BuildingPos(0, target.x, target.y)))
}

private fun handleResolve(ex: HttpExchange) {
    val b = pick(ex)
    val q = query(ex)
    val text = URLDecoder.decode(q["q"] ?: "", "UTF-8").trim()
    if (text.isEmpty()) { respond(ex, "application/json", "{\"ok\":false,\"error\":\"empty query\"}"); return }
    val rooms = b.rooms
        ?: run { respond(ex, "application/json", "{\"ok\":false,\"error\":\"no room map for this building\"}"); return }
    val apiKey = (System.getenv("GEMINI_API_KEY") ?: System.getenv("GOOGLE_API_KEY"))?.takeIf { it.isNotBlank() }
        ?: run { respond(ex, "application/json", "{\"ok\":false,\"error\":\"GEMINI_API_KEY not set on server\"}"); return }
    val cx = q["cx"]?.toDoubleOrNull()
    val cy = q["cy"]?.toDoubleOrNull()
    val model = System.getenv("GEMINI_MODEL")?.takeIf { it.isNotBlank() }
    val box = try {
        if (model != null) DestinationResolver.resolve(rooms, text, cx, cy, apiKey, model)
        else DestinationResolver.resolve(rooms, text, cx, cy, apiKey)
    } catch (e: Exception) {
        respond(ex, "application/json", "{\"ok\":false,\"error\":${jsonStr(e.message ?: "resolve failed")}}")
        return
    }
    val nums = Regex("-?\\d+").findAll(box).map { it.value.toInt() }.toList()
    if (box.trim() == "-1" || nums.size < 4) {
        respond(ex, "application/json", "{\"ok\":true,\"box\":null}")
    } else {
        respond(ex, "application/json", "{\"ok\":true,\"box\":[${nums[0]},${nums[1]},${nums[2]},${nums[3]}]}")
    }
}

private fun jsonStr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\""

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

private fun voronoiJson(b: Registered): String {
    val sb = StringBuilder("{\"floors\":[")
    b.grid.floors.forEachIndexed { fi, f ->
        if (fi > 0) sb.append(",")
        sb.append("{\"index\":").append(f.index).append(",\"cells\":[")
        b.voronoi.voronoiCells(f).forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append("[").append(p.x).append(",").append(p.y).append("]")
        }
        sb.append("]}")
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
  <div class="grp"><label>Planner</label><select id="planner" onchange="onPlannerChange()">
    <option value="astar">A* (clearance + smoothing)</option>
    <option value="voronoi">Voronoi (medial axis)</option>
  </select></div>
  <div class="grp"><label>Overlay</label><label style="font-size:13px;display:flex;align-items:center;gap:6px;padding:6px 0">
    <input type="checkbox" id="showrooms" checked onchange="redraw()" style="width:auto"> rooms</label></div>
  <div class="grp"><label>Start (floor, x, y)</label>
    <div class="row"><input id="sf" placeholder="f"><input id="sx" placeholder="x"><input id="sy" placeholder="y"></div></div>
  <div class="grp"><label>Target (floor, x, y)</label>
    <div class="row"><input id="tf" placeholder="f"><input id="tx" placeholder="x"><input id="ty" placeholder="y"></div></div>
  <div class="grp"><label>Destination (spoken)</label>
    <div class="row"><input id="dest" placeholder="e.g. room 31 / the elevator" style="width:210px">
      <button class="alt" onclick="resolveDest()">Resolve &amp; route</button></div></div>
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
  <span><i class="sw" style="background:#5ac8eb"></i>Voronoi boundary</span>
  <span><i class="sw" style="background:#ffe24d"></i>room</span>
  <span><i class="sw" style="background:#ffb020"></i>elevator / stairs</span>
  <span><i class="sw" style="background:#ff5cf0"></i>resolved destination</span>
</div>
<div id="floors"></div>
<script>
var CS=26, building=null, segments=null, clickTarget=false, autoSampleDone=false;
var COL={wall:[43,52,64], portal:[106,169,255], walk:[232,237,242]};
var skeletonCache={}, roomsCache={}, resolvedBox=null;
function el(id){return document.getElementById(id);}
function val(id){var v=parseInt(el(id).value);return isNaN(v)?null:v;}
function curId(){return el('building').value;}
function plannerId(){return el('planner').value;}
function onPlannerChange(){
  var c=[val('sf'),val('sx'),val('sy'),val('tf'),val('tx'),val('ty')];
  var hasRoute=c.every(function(v){return v!==null;});
  if(plannerId()==='voronoi'){ ensureSkeleton(function(){ if(hasRoute) plan(); else redraw(); }); }
  else { if(hasRoute) plan(); else redraw(); }
}
function skeletonFor(idx){ var m=skeletonCache[curId()]; return m?m[idx]:null; }
function ensureSkeleton(cb){
  var id=curId();
  if(skeletonCache[id]){ cb(); return; }
  el('status').textContent='Building Voronoi boundaries...';
  fetch('/voronoi?id='+encodeURIComponent(id)).then(function(r){return r.json();}).then(function(res){
    var m={}; res.floors.forEach(function(fl){ m[fl.index]=fl.cells; }); skeletonCache[id]=m; cb();
  });
}
function drawSkeleton(f,cv){
  if(plannerId()!=='voronoi'||!cv) return;
  var cells=skeletonFor(f.index); if(!cells) return;
  var g=cv.getContext('2d');
  if(CS===1){ g.fillStyle='rgba(90,200,235,0.65)'; for(var i=0;i<cells.length;i++){ g.fillRect(cells[i][0],cells[i][1],1,1); } }
  else { g.fillStyle='rgba(90,200,235,0.5)'; for(var i=0;i<cells.length;i++){ g.fillRect(cells[i][0]*CS+CS*0.3,cells[i][1]*CS+CS*0.3,Math.max(2,CS*0.4),Math.max(2,CS*0.4)); } }
}
function resolveDest(){
  var text=el('dest').value.trim();
  if(!text){ el('status').textContent='Enter a destination phrase.'; return; }
  var sf=val('sf'),sx=val('sx'),sy=val('sy');
  if([sf,sx,sy].some(function(v){return v===null;})){ el('status').textContent='Set a start first (click a cell or type) - it is used as your current location for "nearest" queries.'; return; }
  var qs='/resolve?id='+encodeURIComponent(curId())+'&q='+encodeURIComponent(text)+'&cx='+sx+'&cy='+sy;
  el('status').textContent='Resolving "'+text+'" via Gemini...';
  fetch(qs).then(function(r){return r.json();}).then(function(res){
    if(!res.ok){ el('status').textContent='Resolve failed: '+(res.error||'unknown'); return; }
    if(!res.box){ resolvedBox=null; redraw(); el('status').textContent='Could not match "'+text+'" to a destination.'; return; }
    resolvedBox=res.box;
    var cx=Math.round((res.box[0]+res.box[2])/2), cy=Math.round((res.box[1]+res.box[3])/2);
    el('tf').value=0; el('tx').value=cx; el('ty').value=cy;
    el('status').textContent='Resolved "'+text+'" to ['+res.box.join(',')+'] - routing to center ('+cx+','+cy+').';
    plan();
  });
}
function drawResolvedBox(f,cv){
  if(!resolvedBox||f.index!==0||!cv) return;
  var g=cv.getContext('2d');
  var x0=resolvedBox[0]*CS, y0=resolvedBox[1]*CS, x1=resolvedBox[2]*CS, y1=resolvedBox[3]*CS;
  g.lineWidth=Math.max(2,CS*1.5); g.strokeStyle='#ff5cf0'; g.setLineDash([8,5]);
  g.strokeRect(x0,y0,x1-x0,y1-y0); g.setLineDash([]);
}
function roomsFor(idx){ return idx===0 ? (roomsCache[curId()]||null) : null; }
function ensureRooms(cb){
  var id=curId();
  if(roomsCache[id]){ cb(); return; }
  fetch('/rooms?id='+encodeURIComponent(id)).then(function(r){return r.json();}).then(function(res){
    roomsCache[id]=res.items||[]; cb();
  });
}
function drawRooms(f,cv){
  if(!el('showrooms').checked||!cv) return;
  var items=roomsFor(f.index); if(!items||!items.length) return;
  var g=cv.getContext('2d');
  items.forEach(function(it){
    var xs=it.polygon.map(function(p){return p[0];}), ys=it.polygon.map(function(p){return p[1];});
    var x0=Math.min.apply(null,xs)*CS, y0=Math.min.apply(null,ys)*CS;
    var x1=Math.max.apply(null,xs)*CS, y1=Math.max.apply(null,ys)*CS;
    var notable=it.type!=='room';
    if(notable){ g.fillStyle='rgba(255,176,32,0.22)'; g.fillRect(x0,y0,x1-x0,y1-y0); }
    g.lineWidth=Math.max(1,CS); g.strokeStyle=notable?'#ffb020':'#ffe24d';
    g.strokeRect(x0,y0,x1-x0,y1-y0);
    g.fillStyle=notable?'#ffb020':'#ffe24d'; g.font='bold 12px system-ui'; g.textAlign='left';
    g.fillText(it.id, x0+4, y0+14);
  });
}

fetch('/buildings').then(function(r){return r.json();}).then(function(list){
  var sel=el('building'); sel.innerHTML='';
  list.forEach(function(b){
    var o=document.createElement('option'); o.value=b.id;
    o.textContent=b.label+' ['+b.width+'x'+b.height+', '+b.floors+' floor(s)]';
    sel.appendChild(o);
  });
  var qp=new URLSearchParams(window.location.search);
  if(qp.get('planner')) el('planner').value=qp.get('planner');
  if(qp.get('sf')!==null){ el('sf').value=qp.get('sf'); el('sx').value=qp.get('sx'); el('sy').value=qp.get('sy'); }
  if(qp.get('dest')) el('dest').value=qp.get('dest');
  if(list.length){ sel.value=list[list.length-1].id; loadBuilding(true); }
});

function onBuildingChange(){ segments=null; clearFields(); loadBuilding(false); }

function loadBuilding(autoSample){
  fetch('/building?id='+encodeURIComponent(curId())).then(function(r){return r.json();}).then(function(b){
    building=b;
    var maxW=0; b.floors.forEach(function(f){ if(f.width>maxW) maxW=f.width; });
    CS=Math.max(1, Math.min(26, Math.floor(720/maxW)));
    renderFloors();
    ensureRooms(function(){
      var after=function(){
        if(autoSample && !autoSampleDone && el('dest').value && val('sx')!==null){ autoSampleDone=true; resolveDest(); return; }
        if(autoSample && !autoSampleDone){ autoSampleDone=true; runSample(); }
      };
      if(plannerId()==='voronoi'){ ensureSkeleton(function(){ redraw(); after(); }); } else { redraw(); after(); }
    });
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
    drawFloor(f,cv); drawSkeleton(f,cv); drawRooms(f,cv); drawResolvedBox(f,cv);
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
// Ramer-Douglas-Peucker corner extraction (grid coords).
function rdp(pts, eps){
  if(pts.length<3) return pts.slice();
  var keep=new Array(pts.length); keep[0]=true; keep[pts.length-1]=true;
  var stack=[[0,pts.length-1]];
  while(stack.length){
    var seg=stack.pop(), lo=seg[0], hi=seg[1], maxD=-1, idx=-1;
    for(var i=lo+1;i<hi;i++){ var d=perpDist(pts[i],pts[lo],pts[hi]); if(d>maxD){maxD=d;idx=i;} }
    if(idx>-1 && maxD>eps){ keep[idx]=true; stack.push([lo,idx]); stack.push([idx,hi]); }
  }
  var out=[]; for(var i=0;i<pts.length;i++) if(keep[i]) out.push(pts[i]); return out;
}
function perpDist(p,a,b){
  var dx=b[0]-a[0], dy=b[1]-a[1], l2=dx*dx+dy*dy;
  if(l2===0){ var ex=p[0]-a[0], ey=p[1]-a[1]; return Math.sqrt(ex*ex+ey*ey); }
  return Math.abs(dy*(p[0]-a[0])-dx*(p[1]-a[1]))/Math.sqrt(l2);
}
// Stroke a polyline with fixed-radius rounded corners (a quadratic spline at each vertex).
function strokeRounded(g, pts, R){
  if(pts.length<2) return;
  g.beginPath(); g.moveTo(pts[0][0],pts[0][1]);
  if(pts.length===2){ g.lineTo(pts[1][0],pts[1][1]); g.stroke(); return; }
  for(var i=1;i<pts.length-1;i++){
    var A=pts[i-1], V=pts[i], B=pts[i+1];
    var ax=V[0]-A[0], ay=V[1]-A[1], bx=B[0]-V[0], by=B[1]-V[1];
    var la=Math.hypot(ax,ay), lb=Math.hypot(bx,by);
    if(la===0||lb===0) continue;
    var r=Math.min(R, la/2, lb/2);
    g.lineTo(V[0]-ax/la*r, V[1]-ay/la*r);
    g.quadraticCurveTo(V[0], V[1], V[0]+bx/lb*r, V[1]+by/lb*r);
  }
  g.lineTo(pts[pts.length-1][0], pts[pts.length-1][1]);
  g.stroke();
}
function drawOverlay(){
  if(!segments) return;
  segments.forEach(function(s){
    if(s.type==='walk'){
      var cv=floorCanvas(s.floor); if(!cv) return; var g=cv.getContext('2d');
      // faint raw path underneath
      g.strokeStyle='rgba(255,140,0,0.28)'; g.lineWidth=Math.max(1,CS*0.4); g.lineJoin='round'; g.lineCap='round'; g.beginPath();
      s.path.forEach(function(p,i){ var cx=p[0]*CS+CS/2, cy=p[1]*CS+CS/2; if(i===0) g.moveTo(cx,cy); else g.lineTo(cx,cy); });
      g.stroke();
      // smooth spline through the path's corner waypoints
      var corners=rdp(s.path, 3).map(function(p){ return [p[0]*CS+CS/2, p[1]*CS+CS/2]; });
      g.strokeStyle='#ff8c00'; g.lineWidth=Math.max(2,CS*0.7); g.lineJoin='round'; g.lineCap='round';
      strokeRounded(g, corners, Math.max(16, CS*32));
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
function redraw(){ building.floors.forEach(function(f){ var cv=floorCanvas(f.index); drawFloor(f,cv); drawSkeleton(f,cv); drawRooms(f,cv); drawResolvedBox(f,cv); }); drawOverlay(); }
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
  var qs='/plan?id='+encodeURIComponent(curId())+'&planner='+plannerId()+'&sf='+sf+'&sx='+sx+'&sy='+sy+'&tf='+tf+'&tx='+tx+'&ty='+ty;
  el('status').textContent='Planning...';
  fetch(qs).then(function(r){return r.json();}).then(function(res){ applyResult(res); });
}
function runSample(){
  el('status').textContent='Running sample test...';
  fetch('/sample?id='+encodeURIComponent(curId())+'&planner='+plannerId()).then(function(r){return r.json();}).then(function(res){
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
function clearRoute(){ segments=null; resolvedBox=null; el('dest').value=''; clearFields(); el('status').textContent='Cleared.'; redraw(); }
</script>
</body>
</html>
""".trimIndent()

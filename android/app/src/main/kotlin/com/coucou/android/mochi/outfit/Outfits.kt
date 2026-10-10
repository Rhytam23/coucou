package com.coucou.android.mochi.outfit

import com.coucou.android.core.Ease
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Mochi's outfits, a line-by-line port of windows/src/mochi/outfits.ts (itself a port of the Mac's
 * MochiOutfitDrawing.swift). Coordinates are the body space of [Head]. Every function keeps the order of
 * its drawing calls, because a test records them and compares with the TypeScript original.
 */
private typealias Stops = List<Pair<Double, String>>

private fun Ctx2D.lin(x0: Double, y0: Double, x1: Double, y1: Double, stops: Stops) = createLinearGradient(x0, y0, x1, y1, stops)
private fun Ctx2D.rad(x: Double, y: Double, r0: Double, r1: Double, stops: Stops) = createRadialGradient(x, y, r0, max(0.001, r1), stops)

private fun Ctx2D.polyline(pts: List<Pt2>) {
    beginPath()
    pts.forEachIndexed { i, q -> if (i > 0) lineTo(q.x, q.y) else moveTo(q.x, q.y) }
}

private fun Ctx2D.roundRect(x: Double, y: Double, w: Double, h: Double, r: Double) {
    val rr = max(0.0, min(r, min(w / 2, h / 2)))
    beginPath()
    moveTo(x + rr, y)
    arcTo(x + w, y, x + w, y + h, rr)
    arcTo(x + w, y + h, x, y + h, rr)
    arcTo(x, y + h, x, y, rr)
    arcTo(x, y, x + w, y, rr)
    closePath()
}

private fun Ctx2D.fillAll(h: Head) = fillRect(-h.rx * 4, -h.ry * 4, h.rx * 8, h.ry * 8)

private fun List<P3>.pts() = map { Pt2(it.x, it.y) }

// ── Soft bits ─────────────────────────────────────────────────────────────────

private fun pompom(ctx: Ctx2D, x: Double, y: Double, r: Double, base: String = "#FFFFFF", shade: String = "#D5D9E2") {
    ctx.save()
    ctx.translate(x, y)
    val n = 11
    for (i in 0 until n) {
        val a = i.toDouble() / n * PI * 2
        val br = r * (0.34 + 0.06 * sin(i * 2.3))
        val bx = cos(a) * r * 0.78
        val by = sin(a) * r * 0.78
        ctx.fillStyle = ctx.rad(bx - br * 0.4, by - br * 0.5, 0.0, br * 1.3, listOf(0.0 to base, 1.0 to shade))
        ctx.beginPath()
        ctx.arc(bx, by, br, 0.0, PI * 2)
        ctx.fill()
    }
    ctx.fillStyle = ctx.rad(-r * 0.3, -r * 0.35, 0.0, r * 1.05, listOf(0.0 to base, 0.7 to base, 1.0 to shade))
    ctx.beginPath()
    ctx.arc(0.0, 0.0, r * 0.86, 0.0, PI * 2)
    ctx.fill()
    ctx.restore()
}

/** Fuzzy band along a polyline (the Santa hat's trim). */
private fun fuzzyBand(ctx: Ctx2D, arc: List<Pt2>, thick: Double, base: String = "#FFFFFF", shade: String = "#DADDE4") {
    if (arc.size < 2) return
    ctx.save()
    ctx.lineJoin = Join.ROUND
    ctx.lineCap = Cap.ROUND
    ctx.polyline(arc)
    ctx.strokeStyle(shade)
    ctx.lineWidth = thick
    ctx.stroke()
    ctx.polyline(arc)
    ctx.strokeStyle(base)
    ctx.lineWidth = thick * 0.78
    ctx.stroke()
    val step = max(2, floor(arc.size / 16.0).toInt())
    var i = 0
    while (i < arc.size) {
        val q = arc[i]
        val r = thick * (0.32 + 0.1 * sin(i * 1.7))
        ctx.fillStyle = ctx.rad(q.x - r * 0.3, q.y - thick * 0.35 - r * 0.3, 0.0, r * 1.2, listOf(0.0 to base, 1.0 to shade))
        ctx.beginPath()
        ctx.arc(q.x, q.y - thick * 0.32, r, 0.0, PI * 2)
        ctx.fill()
        i += step
    }
    ctx.restore()
}

// ── Beanie ────────────────────────────────────────────────────────────────────

private fun beanie(ctx: Ctx2D, h: Head, body: Path2D, simple: Boolean) {
    val s = 1.035
    val yEdge = 0.42
    val yCuff = 0.58
    val head = bodyOutline(h.rx * s, h.ry * s)

    // shadow on the head under the cuff
    ctx.save()
    ctx.clip(body)
    ctx.clip(capClip(h, yEdge - 0.12, 1.0))
    ctx.fillStyle("rgba(30,40,70,0.10)")
    ctx.fillAll(h)
    ctx.restore()

    // knit body
    ctx.save()
    ctx.clip(capClip(h, yCuff, s))
    ctx.fillStyle = ctx.lin(h.rx * 0.5, -h.ry * 1.1, -h.rx * 0.6, h.ry * 0.2, listOf(0.0 to "#7DB6FF", 1.0 to "#2F6FE0"))
    ctx.fill(head)
    if (!simple) {
        ctx.clip(head)
        ctx.strokeStyle("rgba(20,50,140,0.16)")
        ctx.lineWidth = h.R * 0.045
        ctx.lineCap = Cap.ROUND
        for (k in -6..6) {
            val lon = k * 0.24
            val pts = ArrayList<P3>()
            for (i in 0..16) {
                val q = proj(h, surf(yCuff + (1.05 - yCuff) * i / 16, lon, s))
                if (q.z > 0) pts.add(q)
            }
            if (pts.size < 2) continue
            ctx.polyline(pts.pts())
            ctx.stroke()
        }
    }
    ctx.restore()

    // cuff: the band between yEdge and yCuff
    val cuffHead = bodyOutline(h.rx * s * 1.04, h.ry * s * 1.04)
    ctx.save()
    ctx.clip(capClip(h, yEdge, s * 1.04))
    ctx.clip(invert(capClip(h, yCuff, s * 1.04), h), true)
    ctx.fillStyle = ctx.lin(0.0, -h.ry * 0.6, 0.0, -h.ry * 0.2, listOf(0.0 to "#3C7BEA", 1.0 to "#2257C4"))
    ctx.fill(cuffHead)
    ctx.clip(cuffHead)
    ctx.strokeStyle("rgba(10,30,100,0.22)")
    ctx.lineWidth = h.R * 0.035
    for (k in -14..14) {
        val lon = k * 0.115
        val a = proj(h, surf(yEdge, lon, s * 1.04))
        val b = proj(h, surf(yCuff, lon, s * 1.04))
        if (a.z < 0) continue
        ctx.beginPath()
        ctx.moveTo(a.x, a.y)
        ctx.lineTo(b.x, b.y)
        ctx.stroke()
    }
    ctx.restore()

    // top highlight
    ctx.save()
    ctx.clip(capClip(h, yCuff, s))
    ctx.clip(head)
    ctx.fillStyle = ctx.rad(h.rx * 0.3, -h.ry * 0.85, 0.0, h.R * 0.45, listOf(0.0 to "rgba(255,255,255,0.35)", 1.0 to "rgba(255,255,255,0)"))
    ctx.fill(head)
    ctx.restore()

    // pompom on a short spring
    val top = proj(h, 0.0, 1.08 * s, 0.0)
    pompom(ctx, top.x + h.physDx * h.rx * 0.25, top.y - h.R * 0.12 + h.physDy * h.ry * 0.15, h.R * 0.24)
}

// ── Santa hat ─────────────────────────────────────────────────────────────────

private fun santaHat(ctx: Ctx2D, h: Head, body: Path2D) {
    val s = 1.05
    val yEdge = 0.52
    val arc = frontArc(h, yEdge, s)
    if (arc.isEmpty()) return
    val l = arc[0]
    val rt = arc[arc.size - 1]
    val crown = proj(h, 0.0, 1.05, 0.0)
    // the tip flops to the right and down, plus the spring lag
    val tip = Pt2(crown.x + h.rx * (0.95 + h.physDx * 0.35), crown.y + h.ry * (0.05 + h.physDy * 0.2))
    val peak = Pt2(crown.x + h.rx * 0.25, crown.y - h.ry * 0.62)
    val bag = Path2D()
    bag.moveTo(l.x, l.y)
    bag.bezierCurveTo(l.x - h.rx * 0.05, l.y - h.ry * 0.7, peak.x - h.rx * 0.55, peak.y - h.ry * 0.05, peak.x, peak.y)
    bag.quadraticCurveTo(tip.x - h.rx * 0.05, peak.y - h.ry * 0.02, tip.x, tip.y)
    bag.quadraticCurveTo(tip.x - h.rx * 0.12, tip.y - h.ry * 0.22, peak.x + h.rx * 0.18, peak.y + h.ry * 0.32)
    bag.bezierCurveTo(rt.x + h.rx * 0.05, peak.y + h.ry * 0.45, rt.x + h.rx * 0.08, rt.y - h.ry * 0.35, rt.x, rt.y)
    for (i in arc.indices.reversed()) bag.lineTo(arc[i].x, arc[i].y)
    bag.closePath()

    ctx.save()
    ctx.clip(body)
    ctx.clip(capClip(h, yEdge - 0.14, 1.0))
    ctx.fillStyle("rgba(120,10,10,0.10)")
    ctx.fillAll(h)
    ctx.restore()

    ctx.fillStyle = ctx.lin(-h.rx * 0.6, -h.ry * 1.6, h.rx * 0.7, -h.ry * 0.3, listOf(0.0 to "#FF6B6B", 0.55 to "#E53935", 1.0 to "#B71C1C"))
    ctx.fill(bag)

    // folds following the flop
    ctx.save()
    ctx.clip(bag)
    ctx.lineCap = Cap.ROUND
    ctx.strokeStyle("rgba(90,0,0,0.20)")
    for ((a, b, w) in listOf(Triple(0.15, 0.55, 0.1), Triple(0.45, 0.85, 0.08))) {
        ctx.beginPath()
        ctx.moveTo(peak.x - h.rx * 0.1 + (rt.x - l.x) * a * 0.3, peak.y + h.ry * 0.15)
        ctx.quadraticCurveTo(peak.x + h.rx * 0.35, peak.y + h.ry * (0.05 + a * 0.3), tip.x - h.rx * (0.45 - b * 0.3), tip.y - h.ry * 0.12)
        ctx.lineWidth = h.R * w
        ctx.stroke()
    }
    ctx.fillStyle = ctx.rad(peak.x - h.rx * 0.25, peak.y + h.ry * 0.05, 0.0, h.R * 0.5, listOf(0.0 to "rgba(255,255,255,0.32)", 1.0 to "rgba(255,255,255,0)"))
    ctx.fill(bag)
    ctx.restore()

    fuzzyBand(ctx, arc.pts(), h.R * 0.3)
    pompom(ctx, tip.x, tip.y + h.R * 0.04, h.R * 0.22)
}

// ── Party hat ─────────────────────────────────────────────────────────────────

private fun partyHat(ctx: Ctx2D, h: Head, simple: Boolean) {
    val baseY = 0.82
    val baseR = 0.42
    val lean = -0.24 + h.physDx * 0.12
    val c = proj(h, 0.16, baseY + 0.06, 0.0)
    val rim = ArrayList<P3>()
    for (i in 0..48) {
        val a = i.toDouble() / 48 * PI * 2
        rim.add(proj(h, 0.16 + baseR * sin(a), baseY + 0.06, baseR * cos(a)))
    }
    val left = rim.reduce { m, q -> if (q.x < m.x) q else m }
    val right = rim.reduce { m, q -> if (q.x > m.x) q else m }
    val hh = h.ry * 1.6
    val apex = Pt2(c.x + sin(lean) * hh, c.y - cos(lean) * hh)
    val front = frontSilhouette(rim)

    val cone = Path2D()
    cone.moveTo(left.x, left.y)
    cone.quadraticCurveTo((left.x + apex.x) / 2 - h.rx * 0.06, (left.y + apex.y) / 2, apex.x - h.R * 0.05, apex.y + h.R * 0.06)
    cone.quadraticCurveTo(apex.x, apex.y - h.R * 0.03, apex.x + h.R * 0.05, apex.y + h.R * 0.06)
    cone.quadraticCurveTo((right.x + apex.x) / 2 + h.rx * 0.06, (right.y + apex.y) / 2, right.x, right.y)
    for (i in front.indices.reversed()) cone.lineTo(front[i].x, front[i].y)
    cone.closePath()
    ctx.fillStyle = ctx.lin(left.x, apex.y, right.x, left.y, listOf(0.0 to "#FF9BD0", 0.5 to "#F15BAE", 1.0 to "#C2187A"))
    ctx.fill(cone)

    ctx.save()
    ctx.clip(cone)
    if (!simple) {
        ctx.fillStyle("rgba(255,255,255,0.92)")
        val dots = listOf(
            0.25 to -0.35, 0.3 to 0.3, 0.55 to -0.05, 0.72 to 0.28, 0.8 to -0.3, 0.45 to 0.6, 0.48 to -0.65,
        )
        for ((t, u) in dots) {
            val bx = left.x + (right.x - left.x) * (0.5 + u * 0.5)
            val by = left.y + (right.y - left.y) * (0.5 + u * 0.5)
            val x = bx + (apex.x - bx) * (1 - t)
            val y = by + (apex.y - by) * (1 - t)
            val r = h.R * 0.075 * (0.6 + t * 0.5)
            ctx.beginPath()
            ctx.ellipse(x, y, r, r * 0.9, 0.0, 0.0, PI * 2)
            ctx.fill()
        }
    }
    ctx.fillStyle = ctx.lin(left.x, 0.0, right.x, 0.0, listOf(0.0 to "rgba(255,255,255,0.28)", 0.35 to "rgba(255,255,255,0)", 1.0 to "rgba(80,0,40,0.18)"))
    ctx.fill(cone)
    ctx.restore()

    if (front.size > 1) {
        ctx.polyline(front.pts())
        ctx.strokeStyle("#FFD84D")
        ctx.lineWidth = h.R * 0.07
        ctx.lineCap = Cap.ROUND
        ctx.stroke()
    }
    pompom(ctx, apex.x, apex.y - h.R * 0.04, h.R * 0.16, "#FFE27A", "#F2B705")
}

// ── Crown ─────────────────────────────────────────────────────────────────────

private const val CROWN_YB = 0.46

private class CrownSeg(val b: P3, val tt: P3, val z: Double)

/** side -1: the back half, behind the head; +1: the front half. */
private fun crownPart(ctx: Ctx2D, h: Head, side: Int, simple: Boolean) {
    val s = 1.06
    val yb = CROWN_YB
    val yt = 0.66
    val n = 8
    val spikeH = 0.42
    val bigN = 120
    val seg = ArrayList<CrownSeg>()
    for (i in 0..bigN) {
        val lon = -PI + i.toDouble() / bigN * 2 * PI
        val b = proj(h, surf(yb, lon, s))
        val phase = (lon + PI) / (2 * PI) * n
        val f = phase - floor(phase)
        val spike = max(0.0, 1 - abs(f - 0.5) * 2).pow(1.6)
        val sp = surf(yt, lon, s)
        val tt = proj(h, sp.x * (1 - 0.08 * spike), yt + spikeH * spike, sp.z * (1 - 0.08 * spike))
        seg.add(CrownSeg(b, tt, b.z))
    }
    val keep = seg.filter { q -> if (side > 0) q.z >= 0 else q.z < 0.02 }.sortedBy { it.b.x }
    if (keep.size < 2) return
    val shape = Path2D()
    keep.forEachIndexed { i, q -> if (i > 0) shape.lineTo(q.tt.x, q.tt.y) else shape.moveTo(q.tt.x, q.tt.y) }
    for (i in keep.indices.reversed()) shape.lineTo(keep[i].b.x, keep[i].b.y)
    shape.closePath()

    val dark = side < 0
    ctx.fillStyle = ctx.lin(
        0.0, -h.ry * 1.05, 0.0, -h.ry * 0.45,
        if (dark) listOf(0.0 to "#C98A12", 1.0 to "#8A5A06") else listOf(0.0 to "#FFE58A", 0.5 to "#FBBF24", 1.0 to "#D08A0B"),
    )
    ctx.fill(shape)
    if (dark) return

    ctx.save()
    ctx.clip(shape)
    ctx.fillStyle = ctx.lin(
        -h.rx, 0.0, h.rx, 0.0,
        listOf(0.0 to "rgba(120,70,0,0.25)", 0.45 to "rgba(255,255,255,0)", 0.62 to "rgba(255,255,255,0.35)", 1.0 to "rgba(120,70,0,0.25)"),
    )
    ctx.fill(shape)
    ctx.restore()
    if (simple) return

    val gems = listOf("#EF4444", "#3B82F6", "#22C55E", "#A855F7")
    for (k in 0 until n) {
        val lon = -PI + (k + 0.5) / n * 2 * PI
        val sp = surf(yt, lon, s)
        val tipP = proj(h, sp.x * 0.92, yt + spikeH, sp.z * 0.92)
        val mid = proj(h, surf((yb + yt) / 2, lon, s * 1.01))
        if (mid.z <= 0.12) continue
        val r = h.R * 0.055
        ctx.beginPath()
        ctx.arc(tipP.x, tipP.y - r * 0.5, r, 0.0, PI * 2)
        ctx.fillStyle = ctx.rad(tipP.x - r * 0.3, tipP.y - r, 0.0, r * 1.2, listOf(0.0 to "#FFF6CC", 1.0 to "#E0A21A"))
        ctx.fill()
        val gr = h.R * 0.075
        ctx.beginPath()
        ctx.ellipse(mid.x, mid.y, gr * max(0.35, mid.z), gr, 0.0, 0.0, PI * 2)
        ctx.fillStyle(gems[k % gems.size])
        ctx.fill()
        ctx.beginPath()
        ctx.arc(mid.x - gr * 0.25 * mid.z, mid.y - gr * 0.35, gr * 0.28, 0.0, PI * 2)
        ctx.fillStyle("rgba(255,255,255,0.75)")
        ctx.fill()
    }
}

private fun crownFront(ctx: Ctx2D, h: Head, body: Path2D, simple: Boolean) {
    // shadow of the band on the head
    ctx.save()
    ctx.clip(body)
    ctx.clip(capClip(h, CROWN_YB - 0.1, 1.0))
    ctx.clip(invert(capClip(h, CROWN_YB, 1.0), h), true)
    ctx.fillStyle("rgba(80,50,0,0.12)")
    ctx.fillAll(h)
    ctx.restore()
    crownPart(ctx, h, 1, simple)
}

// ── Witch hat ─────────────────────────────────────────────────────────────────

private fun witchBrim(h: Head): List<P3> {
    val y = 0.7
    val rr = 1.42
    val pts = ArrayList<P3>()
    for (i in 0..120) {
        val a = -PI + i.toDouble() / 120 * 2 * PI
        val wob = 1 + 0.035 * sin(a * 3 + 0.6)
        val droop = -0.1 * abs(sin(a)).pow(2) // the edges droop a little
        pts.add(proj(h, rr * wob * sin(a), y + droop, rr * wob * cos(a)))
    }
    return pts
}

private fun closedPath(pts: List<Pt2>): Path2D {
    val p = Path2D()
    pts.forEachIndexed { i, q -> if (i > 0) p.lineTo(q.x, q.y) else p.moveTo(q.x, q.y) }
    p.closePath()
    return p
}

/** The whole brim, behind the head; the front draws its front half again. */
private fun witchHatBack(ctx: Ctx2D, h: Head) {
    ctx.fillStyle = ctx.lin(0.0, -h.ry, 0.0, -h.ry * 0.4, listOf(0.0 to "#2A0A4F", 1.0 to "#3B0F6B"))
    ctx.fill(closedPath(witchBrim(h).pts()))
}

private fun witchHatFront(ctx: Ctx2D, h: Head, body: Path2D) {
    val all = witchBrim(h)
    val brim = closedPath(all.pts())
    val fr = all.filter { it.z >= 0 }.sortedBy { it.x }

    ctx.save()
    ctx.clip(body)
    ctx.clip(capClip(h, 0.5, 1.0))
    ctx.fillStyle("rgba(40,0,70,0.10)")
    ctx.fillAll(h)
    ctx.restore()

    ctx.fillStyle = ctx.lin(0.0, -h.ry * 0.9, 0.0, -h.ry * 0.3, listOf(0.0 to "#5B21B6", 1.0 to "#3B0764"))
    ctx.fill(brim)
    if (fr.size > 1) {
        ctx.polyline(fr.pts())
        ctx.strokeStyle("rgba(190,150,255,0.35)")
        ctx.lineWidth = h.R * 0.035
        ctx.lineCap = Cap.ROUND
        ctx.stroke()
    }

    // cone: base ring r = 0.62 at y = 0.74, tall apex, the tip bends over
    val baseR = 0.62
    val by = 0.74
    val bl = proj(h, -baseR, by, 0.0)
    val br = proj(h, baseR, by, 0.0)
    val c = proj(h, 0.0, by, 0.0)
    val lean = 0.1 + h.physDx * 0.15
    val top = Pt2(c.x + h.rx * 0.18 + sin(lean) * h.ry * 0.3, c.y - h.ry * 1.25)
    val tip = Pt2(top.x + h.rx * (0.45 + h.physDx * 0.25), top.y + h.ry * (0.22 + h.physDy * 0.1))
    val capFront = frontArc(h, by, baseR / ringR(by)).filter { it.x >= bl.x - 1 && it.x <= br.x + 1 }
    val cone = Path2D()
    cone.moveTo(bl.x, bl.y)
    cone.bezierCurveTo(bl.x + h.rx * 0.12, bl.y - h.ry * 0.5, top.x - h.rx * 0.28, top.y + h.ry * 0.25, top.x - h.rx * 0.02, top.y - h.ry * 0.02)
    cone.quadraticCurveTo(top.x + h.rx * 0.25, top.y - h.ry * 0.08, tip.x, tip.y)
    cone.quadraticCurveTo(top.x + h.rx * 0.22, top.y + h.ry * 0.08, top.x + h.rx * 0.14, top.y + h.ry * 0.22)
    cone.bezierCurveTo(br.x - h.rx * 0.18, c.y - h.ry * 0.45, br.x - h.rx * 0.02, br.y - h.ry * 0.2, br.x, br.y)
    for (i in capFront.indices.reversed()) cone.lineTo(capFront[i].x, capFront[i].y)
    cone.closePath()
    ctx.fillStyle = ctx.lin(bl.x, top.y, br.x, bl.y, listOf(0.0 to "#7C3AED", 0.55 to "#4C1D95", 1.0 to "#2E1065"))
    ctx.fill(cone)

    ctx.save()
    ctx.clip(cone)
    ctx.fillStyle = ctx.lin(bl.x, 0.0, br.x, 0.0, listOf(0.0 to "rgba(255,255,255,0.22)", 0.4 to "rgba(255,255,255,0)", 1.0 to "rgba(0,0,0,0.15)"))
    ctx.fill(cone)
    // crease where the tip bends
    ctx.beginPath()
    ctx.moveTo(top.x - h.rx * 0.05, top.y + h.ry * 0.05)
    ctx.quadraticCurveTo(top.x + h.rx * 0.1, top.y + h.ry * 0.12, top.x + h.rx * 0.2, top.y + h.ry * 0.06)
    ctx.strokeStyle("rgba(20,0,40,0.35)")
    ctx.lineWidth = h.R * 0.05
    ctx.lineCap = Cap.ROUND
    ctx.stroke()
    // orange band, just above the base
    val fc = proj(h, 0.0, by, baseR)
    val lift = h.ry * 0.11
    ctx.beginPath()
    ctx.moveTo(bl.x - 2, bl.y - lift)
    ctx.quadraticCurveTo(fc.x, 2 * (fc.y - lift) - (bl.y + br.y) / 2, br.x + 2, br.y - lift)
    ctx.strokeStyle("#F97316")
    ctx.lineWidth = h.ry * 0.17
    ctx.lineCap = Cap.BUTT
    ctx.stroke()
    ctx.restore()

    // buckle
    val bw = h.R * 0.2
    val bh = h.R * 0.16
    ctx.save()
    ctx.translate(fc.x, fc.y - h.ry * 0.11)
    ctx.roundRect(-bw / 2, -bh / 2, bw, bh, bh * 0.25)
    ctx.fillStyle("#FCD34D")
    ctx.fill()
    ctx.roundRect(-bw / 2 + bw * 0.24, -bh / 2 + bh * 0.28, bw * 0.52, bh * 0.44, bh * 0.1)
    ctx.fillStyle("#C2410C")
    ctx.fill()
    ctx.restore()
}

// ── Glasses (pinned to the real eye positions) ────────────────────────────────

private fun sunglasses(ctx: Ctx2D, h: Head, body: Path2D) {
    val eyes = eyeFrames(h)
    val w = h.R * 0.62
    val hh = h.R * 0.46
    ctx.save()
    ctx.clip(body)
    ctx.strokeStyle("#111317")
    ctx.lineCap = Cap.ROUND
    val l = eyes[0]
    val r = eyes[1]
    if (l.visible && r.visible) {
        ctx.beginPath()
        ctx.moveTo(l.x + (w / 2) * l.fx * 0.9, l.y - hh * 0.18)
        ctx.quadraticCurveTo((l.x + r.x) / 2, (l.y + r.y) / 2 - hh * 0.42, r.x - (w / 2) * r.fx * 0.9, r.y - hh * 0.18)
        ctx.lineWidth = h.R * 0.07
        ctx.stroke()
    }
    ctx.lineWidth = h.R * 0.06
    for (e in eyes) {
        if (!e.visible) continue
        ctx.beginPath()
        ctx.moveTo(e.x + (e.sd * w) / 2 * e.fx, e.y - hh * 0.2)
        ctx.lineTo(e.sd * h.rx * 1.05, e.y - hh * 0.35)
        ctx.stroke()
    }
    for (e in eyes) {
        if (!e.visible) continue
        ctx.save()
        ctx.translate(e.x, e.y)
        ctx.scale(e.fx, e.fy)
        ctx.roundRect(-w / 2, -hh / 2, w, hh, hh * 0.42)
        ctx.fillStyle("rgba(17,19,23,0.82)")
        ctx.fill()
        ctx.lineWidth = h.R * 0.05
        ctx.strokeStyle("#0B0C0F")
        ctx.stroke()
        ctx.beginPath()
        ctx.moveTo(-w * 0.28, -hh * 0.05)
        ctx.lineTo(-w * 0.05, -hh * 0.3)
        ctx.strokeStyle("rgba(255,255,255,0.45)")
        ctx.stroke()
        ctx.restore()
    }
    ctx.restore()
}

private fun roundGlasses(ctx: Ctx2D, h: Head, body: Path2D) {
    val eyes = eyeFrames(h)
    val d = h.R * 0.56
    ctx.save()
    ctx.clip(body)
    ctx.strokeStyle("#8A4B12")
    ctx.lineCap = Cap.ROUND
    val l = eyes[0]
    val r = eyes[1]
    if (l.visible && r.visible) {
        ctx.beginPath()
        ctx.moveTo(l.x + (d / 2) * l.fx, l.y - d * 0.08)
        ctx.quadraticCurveTo((l.x + r.x) / 2, (l.y + r.y) / 2 - d * 0.3, r.x - (d / 2) * r.fx, r.y - d * 0.08)
        ctx.lineWidth = h.R * 0.055
        ctx.stroke()
    }
    ctx.lineWidth = h.R * 0.05
    for (e in eyes) {
        if (!e.visible) continue
        ctx.beginPath()
        ctx.moveTo(e.x + (e.sd * d) / 2 * e.fx, e.y - d * 0.1)
        ctx.lineTo(e.sd * h.rx * 1.05, e.y - d * 0.25)
        ctx.stroke()
    }
    for (e in eyes) {
        if (!e.visible) continue
        ctx.save()
        ctx.translate(e.x, e.y)
        ctx.scale(e.fx, e.fy)
        ctx.beginPath()
        ctx.arc(0.0, 0.0, d / 2, 0.0, PI * 2)
        ctx.fillStyle("rgba(190,225,255,0.18)")
        ctx.fill()
        ctx.lineWidth = h.R * 0.065
        ctx.strokeStyle("#9A5A1A")
        ctx.stroke()
        ctx.beginPath()
        ctx.arc(0.0, 0.0, d / 2 - h.R * 0.03, PI * 1.1, PI * 1.45)
        ctx.strokeStyle("rgba(255,255,255,0.55)")
        ctx.lineWidth = h.R * 0.03
        ctx.stroke()
        ctx.restore()
    }
    ctx.restore()
}

// ── Scarf ─────────────────────────────────────────────────────────────────────

private fun scarf(ctx: Ctx2D, h: Head) {
    val s = 1.05
    val y0 = -0.34
    val y1 = -0.66
    val top = frontArc(h, y0, s)
    val bot = frontArc(h, y1, s)
    if (top.isEmpty() || bot.isEmpty()) return
    val band = Path2D()
    top.forEachIndexed { i, q -> if (i > 0) band.lineTo(q.x, q.y) else band.moveTo(q.x, q.y) }
    for (i in bot.indices.reversed()) band.lineTo(bot[i].x, bot[i].y)
    band.closePath()

    ctx.save()
    ctx.clip(bodyOutline(h.rx * s, h.ry * s))
    ctx.fillStyle = ctx.lin(0.0, -h.ry * 0.2, 0.0, h.ry * 0.7, listOf(0.0 to "#F87171", 1.0 to "#B91C1C"))
    ctx.fill(band)
    ctx.save()
    ctx.clip(band)
    ctx.strokeStyle("rgba(255,255,255,0.85)")
    ctx.lineCap = Cap.ROUND
    for (lon in listOf(-1.0, -0.45, 0.1, 0.65, 1.2)) {
        val a = proj(h, surf(y0, lon, s))
        val b = proj(h, surf(y1, lon, s))
        if (a.z < 0) continue
        ctx.beginPath()
        ctx.moveTo(a.x, a.y - 4)
        ctx.lineTo(b.x, b.y + 4)
        ctx.lineWidth = h.R * 0.09 * max(0.3, a.z)
        ctx.stroke()
    }
    ctx.restore()
    ctx.fillStyle = ctx.lin(0.0, -h.ry * 0.5, 0.0, h.ry * 0.3, listOf(0.0 to "rgba(255,255,255,0.18)", 1.0 to "rgba(0,0,0,0.1)"))
    ctx.fill(band)
    ctx.restore()

    // the hanging end, from the knot
    val k = proj(h, surf((y0 + y1) / 2, -0.55, s * 1.03))
    if (k.z <= 0) return
    val sw = h.physDx * h.rx * 0.12
    val end = Path2D()
    end.moveTo(k.x - h.R * 0.16, k.y)
    end.quadraticCurveTo(k.x - h.R * 0.24 + sw, k.y + h.ry * 0.35, k.x - h.R * 0.2 + sw * 1.4, k.y + h.ry * 0.62)
    end.lineTo(k.x + h.R * 0.06 + sw * 1.4, k.y + h.ry * 0.6)
    end.quadraticCurveTo(k.x + h.R * 0.02 + sw, k.y + h.ry * 0.3, k.x + h.R * 0.12, k.y)
    end.closePath()
    ctx.fillStyle = ctx.lin(0.0, k.y, 0.0, k.y + h.ry * 0.6, listOf(0.0 to "#EF4444", 1.0 to "#B91C1C"))
    ctx.fill(end)
    ctx.save()
    ctx.clip(end)
    ctx.fillStyle("rgba(255,255,255,0.85)")
    for (t in listOf(0.35, 0.7)) ctx.fillRect(k.x - h.R * 0.4 + sw, k.y + h.ry * 0.62 * t, h.R * 0.8, h.R * 0.07)
    ctx.restore()
    // fringe
    ctx.strokeStyle("#DC2626")
    ctx.lineWidth = h.R * 0.035
    ctx.lineCap = Cap.ROUND
    for (i in 0 until 4) {
        val fx = k.x - h.R * 0.17 + sw * 1.4 + i * h.R * 0.075
        ctx.beginPath()
        ctx.moveTo(fx, k.y + h.ry * 0.6)
        ctx.lineTo(fx, k.y + h.ry * 0.72)
        ctx.stroke()
    }
    // knot
    ctx.beginPath()
    ctx.ellipse(k.x, k.y, h.R * 0.17, h.R * 0.14, 0.2, 0.0, PI * 2)
    ctx.fillStyle = ctx.rad(k.x - h.R * 0.05, k.y - h.R * 0.05, 0.0, h.R * 0.2, listOf(0.0 to "#F87171", 1.0 to "#B91C1C"))
    ctx.fill()
}

// ── Pumpkin (the body colours come from the engine) ───────────────────────────

val PUMPKIN_BODY: Pair<String, String> = "#FFA94D" to "#E8590C"

private fun pumpkin(ctx: Ctx2D, h: Head, body: Path2D, simple: Boolean) {
    if (!simple) {
        ctx.save()
        ctx.clip(body)
        ctx.lineCap = Cap.ROUND
        for (lon in listOf(-1.15, -0.55, 0.0, 0.55, 1.15)) {
            val pts = ArrayList<P3>()
            for (i in 0..30) {
                val q = proj(h, surf(-0.98 + 1.96 * i / 30, lon, 1.0))
                if (q.z > 0) pts.add(q)
            }
            if (pts.size < 2) continue
            val zz = pts[floor(pts.size / 2.0).toInt()].z
            ctx.polyline(pts.pts())
            ctx.strokeStyle("rgba(150,50,0,${0.22 * zz})")
            ctx.lineWidth = h.R * 0.12
            ctx.stroke()
            ctx.polyline(pts.map { Pt2(it.x + h.R * 0.07, it.y) })
            ctx.strokeStyle("rgba(255,220,170,${0.18 * zz})")
            ctx.lineWidth = h.R * 0.04
            ctx.stroke()
        }
        ctx.restore()
    }
    val t = proj(h, 0.02, 1.0, 0.0)
    // stem
    ctx.beginPath()
    ctx.moveTo(t.x - h.R * 0.09, t.y + h.R * 0.04)
    ctx.quadraticCurveTo(t.x - h.R * 0.08, t.y - h.R * 0.22, t.x + h.R * 0.08, t.y - h.R * 0.3)
    ctx.lineTo(t.x + h.R * 0.13, t.y - h.R * 0.22)
    ctx.quadraticCurveTo(t.x + h.R * 0.04, t.y - h.R * 0.15, t.x + h.R * 0.08, t.y + h.R * 0.04)
    ctx.closePath()
    ctx.fillStyle = ctx.lin(t.x - h.R * 0.1, 0.0, t.x + h.R * 0.1, 0.0, listOf(0.0 to "#65A30D", 1.0 to "#3F6212"))
    ctx.fill()
    // leaf
    ctx.save()
    ctx.translate(t.x - h.R * 0.06, t.y - h.R * 0.02)
    ctx.rotate(-0.5)
    ctx.beginPath()
    ctx.moveTo(0.0, 0.0)
    ctx.quadraticCurveTo(-h.R * 0.18, -h.R * 0.2, -h.R * 0.38, -h.R * 0.02)
    ctx.quadraticCurveTo(-h.R * 0.18, h.R * 0.1, 0.0, 0.0)
    ctx.fillStyle = ctx.lin(0.0, -h.R * 0.15, -h.R * 0.3, 0.0, listOf(0.0 to "#84CC16", 1.0 to "#4D7C0F"))
    ctx.fill()
    ctx.beginPath()
    ctx.moveTo(-h.R * 0.02, -h.R * 0.01)
    ctx.quadraticCurveTo(-h.R * 0.18, -h.R * 0.08, -h.R * 0.32, -h.R * 0.03)
    ctx.strokeStyle("rgba(30,60,0,0.4)")
    ctx.lineWidth = h.R * 0.02
    ctx.lineCap = Cap.ROUND
    ctx.stroke()
    ctx.restore()
    if (simple) return
    // curly tendril
    ctx.beginPath()
    ctx.moveTo(t.x + h.R * 0.1, t.y - h.R * 0.12)
    ctx.bezierCurveTo(t.x + h.R * 0.3, t.y - h.R * 0.25, t.x + h.R * 0.35, t.y - h.R * 0.02, t.x + h.R * 0.22, t.y - h.R * 0.06)
    ctx.strokeStyle("#4D7C0F")
    ctx.lineWidth = h.R * 0.03
    ctx.lineCap = Cap.ROUND
    ctx.stroke()
}

// ── Bow (anchored in 3D, turns with the head) ─────────────────────────────────

private fun bow(ctx: Ctx2D, h: Head) {
    val a = proj(h, surf(0.86, 0.55, 1.02))
    if (a.z < -0.2) return
    val s = h.R * 0.26
    val sq = max(0.45, cos(0.55 + h.yaw))
    ctx.save()
    ctx.translate(a.x, a.y)
    ctx.rotate(0.35 + h.yaw * 0.3)
    ctx.scale(sq, 1.0)
    for (sd in listOf(-1.0, 1.0)) {
        ctx.beginPath()
        ctx.moveTo(0.0, 0.0)
        ctx.bezierCurveTo(sd * s * 0.6, -s * 0.85, sd * s * 1.35, -s * 0.55, sd * s * 1.15, 0.0)
        ctx.bezierCurveTo(sd * s * 1.35, s * 0.55, sd * s * 0.6, s * 0.85, 0.0, 0.0)
        ctx.fillStyle = ctx.lin(0.0, -s, 0.0, s, listOf(0.0 to "#FF8CC6", 1.0 to "#DB2777"))
        ctx.fill()
        ctx.beginPath()
        ctx.moveTo(sd * s * 0.25, -s * 0.05)
        ctx.quadraticCurveTo(sd * s * 0.7, -s * 0.15, sd * s * 0.95, -s * 0.05)
        ctx.strokeStyle("rgba(140,10,70,0.35)")
        ctx.lineWidth = s * 0.08
        ctx.lineCap = Cap.ROUND
        ctx.stroke()
    }
    ctx.beginPath()
    ctx.ellipse(0.0, 0.0, s * 0.24, s * 0.3, 0.0, 0.0, PI * 2)
    ctx.fillStyle = ctx.rad(-s * 0.06, -s * 0.1, 0.0, s * 0.35, listOf(0.0 to "#FFB3D9", 1.0 to "#C2185B"))
    ctx.fill()
    ctx.restore()
}

// ── Bunny ears (always behind the head) ───────────────────────────────────────

private fun bunnyEars(ctx: Ctx2D, h: Head) {
    val r = h.R
    val earH = r * 0.85
    for (sd in listOf(-1.0, 1.0)) {
        val root = proj(h, sd * 0.45, 0.92, 0.0)
        val rootL = proj(h, sd * 0.45 - 0.22, 0.92, 0.0)
        val rootR = proj(h, sd * 0.45 + 0.22, 0.92, 0.0)
        val hw = max(r * 0.04, abs(rootR.x - rootL.x) / 2)
        ctx.save()
        ctx.translate(root.x, root.y - earH * 0.15)
        ctx.beginPath()
        ctx.ellipse(0.0, 0.0, hw, earH / 2, 0.0, 0.0, PI * 2)
        ctx.fillStyle("#F9F0F0")
        ctx.fill()
        ctx.strokeStyle("rgba(0,0,0,0.06)")
        ctx.lineWidth = 0.8
        ctx.stroke()
        ctx.beginPath()
        ctx.ellipse(0.0, -earH / 2 + r * 0.1 + earH * 0.325, hw * 0.5, earH * 0.325, 0.0, 0.0, PI * 2)
        ctx.fillStyle("rgba(252,165,165,0.7)")
        ctx.fill()
        ctx.restore()
    }
}

// ── Layers and transitions ────────────────────────────────────────────────────

/** What Mochi can wear. The raw values are the Mac's and the PC's (stored in the preferences), keep them stable. */
enum class Outfit(val id: String) {
    NONE("none"), PARTY_HAT("partyHat"), BEANIE("beanie"), CROWN("crown"), SUNGLASSES("sunglasses"),
    ROUND_GLASSES("roundGlasses"), BOW("bow"), SCARF("scarf"), WITCH_HAT("witchHat"), PUMPKIN("pumpkin"),
    SANTA_HAT("santaHat"), BUNNY_EARS("bunnyEars");
}

/** Glasses, bow, scarf and pumpkin are on the face: behind the head once it has turned away. */
private val ON_FACE = setOf(Outfit.SUNGLASSES, Outfit.ROUND_GLASSES, Outfit.BOW, Outfit.SCARF, Outfit.PUMPKIN)
private val HATS = setOf(Outfit.BEANIE, Outfit.SANTA_HAT, Outfit.PARTY_HAT, Outfit.CROWN, Outfit.WITCH_HAT)

/** [presence] 0 = gone, 1 = fully on (animated by the engine); [morph] is Mochi's mailbox morph: outfits fade as he turns into a box. */
data class OutfitState(val presence: Double, val morph: Double)

private fun faceTurnedAway(h: Head) = proj(h, 0.0, 0.0, 1.0).z < 0

private fun drawFace(ctx: Ctx2D, outfit: Outfit, h: Head, body: Path2D, simple: Boolean) {
    when (outfit) {
        Outfit.SUNGLASSES -> sunglasses(ctx, h, body)
        Outfit.ROUND_GLASSES -> roundGlasses(ctx, h, body)
        Outfit.SCARF -> scarf(ctx, h)
        Outfit.PUMPKIN -> pumpkin(ctx, h, body, simple)
        Outfit.BOW -> bow(ctx, h)
        else -> {}
    }
}

fun layerAlpha(st: OutfitState): Double {
    val morphFade = 1 - min(1.0, max(0.0, (st.morph - 0.3) / 0.2))
    return morphFade * min(1.0, st.presence * 2.5)
}

/** The parts behind Mochi's body. [ctx] is in body space (translated to the body centre, tilted and squashed like the body). */
fun drawOutfitBehind(ctx: Ctx2D, outfit: Outfit, h: Head, st: OutfitState) {
    if (outfit == Outfit.NONE) return
    val alpha = layerAlpha(st)
    if (alpha <= 0.005) return
    val simple = h.R < SIMPLIFY_BELOW_R
    val body = bodyOutline(h.rx, h.ry)

    if (outfit in ON_FACE) {
        if (faceTurnedAway(h)) ctx.withLayer(alpha) { l -> drawFace(l, outfit, h, body, simple) }
        return
    }
    val posP = Ease.back(st.presence)
    val hatScale = 0.85 + 0.15 * posP
    ctx.save()
    ctx.translate(0.0, -(1 - posP) * h.ry)
    ctx.scale(hatScale, hatScale)
    ctx.withLayer(alpha) { l ->
        when (outfit) {
            Outfit.BUNNY_EARS -> bunnyEars(l, h)
            Outfit.CROWN -> crownPart(l, h, -1, simple)
            Outfit.WITCH_HAT -> witchHatBack(l, h)
            else -> {}
        }
    }
    ctx.restore()
}

/** The parts in front of Mochi, drawn after the body and the eyes. */
fun drawOutfitFront(ctx: Ctx2D, outfit: Outfit, h: Head, st: OutfitState) {
    if (outfit == Outfit.NONE || outfit == Outfit.BUNNY_EARS) return
    if (outfit in ON_FACE && faceTurnedAway(h)) return
    val alpha = layerAlpha(st)
    if (alpha <= 0.005) return
    val simple = h.R < SIMPLIFY_BELOW_R
    val body = bodyOutline(h.rx, h.ry)
    val p = st.presence
    val posP = Ease.back(p)

    ctx.save()
    if (outfit in HATS) {
        // hats drop onto the head and settle
        val hatScale = 0.85 + 0.15 * posP
        ctx.translate(0.0, -(1 - posP) * h.ry)
        ctx.scale(hatScale, hatScale)
    } else if (outfit == Outfit.SUNGLASSES || outfit == Outfit.ROUND_GLASSES) {
        ctx.translate(0.0, (1 - p) * 0.25 * h.ry)
    } else if (outfit == Outfit.SCARF) {
        ctx.translate(0.0, (1 - p) * 0.3 * h.ry)
    } else if (outfit == Outfit.BOW) {
        ctx.scale(max(0.001, posP), max(0.001, posP))
    }
    ctx.withLayer(alpha) { l ->
        when (outfit) {
            Outfit.BEANIE -> beanie(l, h, body, simple)
            Outfit.SANTA_HAT -> santaHat(l, h, body)
            Outfit.PARTY_HAT -> partyHat(l, h, simple)
            Outfit.CROWN -> crownFront(l, h, body, simple)
            Outfit.WITCH_HAT -> witchHatFront(l, h, body)
            else -> drawFace(l, outfit, h, body, simple)
        }
    }
    ctx.restore()
}

// ── Wardrobe icons ────────────────────────────────────────────────────────────

private const val INK = "rgb(26,20,18)"

/** A little Mochi wearing [outfit], centred in a [size] x [size] icon (the PC's iconMochi). */
fun drawIconMochi(ctx: Ctx2D, size: Double, outfit: Outfit) {
    val r = 10.0
    val h = makeHead(r)
    val cx = size / 2
    val cy = size / 2 + r * 0.62
    val st = OutfitState(1.0, 0.0)
    ctx.save()
    ctx.translate(cx, cy)
    drawOutfitBehind(ctx, outfit, h, st)
    val body = bodyOutline(h.rx, h.ry)
    val (top, bottom) = if (outfit == Outfit.PUMPKIN) PUMPKIN_BODY else ("rgb(237,237,239)" to "rgb(196,197,202)")
    ctx.fillStyle = ctx.lin(h.rx * 0.7, -h.ry * 0.85, -h.rx * 0.8, h.ry * 0.9, listOf(0.0 to top, 1.0 to bottom))
    ctx.fill(body)
    ctx.fillStyle = ctx.rad(0.0, 0.0, r * 0.15, r * 1.25, listOf(0.0 to "rgba(0,0,0,0)", 0.6 to "rgba(0,0,0,0)", 1.0 to "rgba(0,0,0,0.2)"))
    ctx.fill(body)
    ctx.fillStyle = ctx.rad(h.rx * 0.34, -h.ry * 0.46, 0.0, r * 0.42, listOf(0.0 to "rgba(255,255,255,0.55)", 1.0 to "rgba(255,255,255,0)"))
    ctx.fill(body)
    ctx.save()
    ctx.clip(body)
    ctx.fillStyle(INK)
    for (e in eyeFrames(h)) {
        if (!e.visible) continue
        ctx.save()
        ctx.translate(e.x, e.y)
        ctx.scale(e.fx, e.fy)
        val hh = max(e.h, e.w * 0.3)
        ctx.roundRect(-e.w / 2, -hh / 2, e.w, hh, min(e.w / 2, hh / 2))
        ctx.fill()
        ctx.restore()
    }
    ctx.restore()
    drawOutfitFront(ctx, outfit, h, st)
    ctx.restore()
}

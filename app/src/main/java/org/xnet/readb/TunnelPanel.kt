package org.xnet.readb

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Dead-simple tunnels/ports UI, built in code into a container LinearLayout.
 *
 * Layout: three collapsible **accordion** sections (Forward / SOCKS5 proxy /
 * Encrypted tunnel). Tap a header to expand it (others collapse). Each section
 * has labeled fillable boxes with the acceptable range shown, inline validation
 * on Add, and a button. Below the accordions is the list of active routes with
 * per-row delete. No dialogs.
 */
class TunnelPanel(
    private val container: LinearLayout,
    private val onChanged: () -> Unit,
) {
    private val ctx: Context = container.context
    private var expanded = 2  // the tunnel section (the only one) is open by default

    private companion object {
        const val ADBD_PORT = 5555          // pinned local adbd target
        const val TUNNEL_SERVER_PORT = 2222 // fixed rendezvous-server port
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun render() {
        container.removeAllViews()
        // Only the encrypted tunnel (remote adb) is offered; Forward and SOCKS5
        // are intentionally hidden for the re-adb use case.
        section(2, "▾  Encrypted tunnel  (remote adb)") { body -> tunnelForm(body) }
        divider()
        header("Active routes")
        routesList()
    }

    // ── accordion scaffolding ──
    private fun section(index: Int, title: String, buildBody: (LinearLayout) -> Unit) {
        val open = expanded == index
        val head = TextView(ctx).apply {
            text = (if (open) "▾" else "▸") + title.drop(1)
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(if (open) 0xFF14263B.toInt() else 0xFF161616.toInt())
            setOnClickListener { expanded = if (open) -1 else index; render() }
        }
        container.addView(head)
        if (open) {
            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(6), dp(14), dp(14))
                setBackgroundColor(0xFF0D0D0D.toInt())
            }
            buildBody(body)
            container.addView(body)
        }
    }

    private fun header(t: String) = container.addView(TextView(ctx).apply {
        text = t; setTextColor(0xFF90CAF9.toInt()); textSize = 13f
        setPadding(dp(14), dp(10), dp(14), dp(4))
    })

    private fun divider() = container.addView(View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(0xFF333333.toInt())
    })

    private fun field(parent: LinearLayout, label: String, hint: String,
                      value: String = "", numeric: Boolean = false): EditText {
        parent.addView(TextView(ctx).apply {
            text = label; setTextColor(0xFFBBBBBB.toInt()); textSize = 12f
            setPadding(0, dp(6), 0, 0)
        })
        val et = EditText(ctx).apply {
            this.hint = hint; setText(value)
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF666666.toInt())
            textSize = 14f
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }
        parent.addView(et)
        return et
    }

    private fun addButton(parent: LinearLayout, text: String, onClick: () -> Unit) {
        parent.addView(Button(ctx).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener { onClick() }
        })
    }

    // ── validation helpers ──
    private fun port(s: String): Int? = s.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    private fun isHex64(s: String) = s.length == 64 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    private fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()

    private fun add(r: Route) {
        val routes = RouteStore.load(ctx).toMutableList()
        routes.add(r); RouteStore.save(ctx, routes)
        expanded = -1
        onChanged(); render()
        toast("Added: ${r.summary()}")
    }

    // ── forms ──
    private fun forwardForm(b: LinearLayout) {
        val listen = field(b, "Listen port  (1–65535)", "e.g. 8080", numeric = true)
        val taddr = field(b, "Target address", "127.0.0.1", "127.0.0.1")
        val tport = field(b, "Target port  (1–65535)", "e.g. 80", numeric = true)
        addButton(b, "Add forward") {
            val lp = port(listen.text.toString()) ?: return@addButton toast("Listen port must be 1–65535")
            val tp = port(tport.text.toString()) ?: return@addButton toast("Target port must be 1–65535")
            add(Route(listenAddr = "127.0.0.1", listenPort = lp,
                targetAddr = taddr.text.toString().ifBlank { "127.0.0.1" }, targetPort = tp))
        }
    }

    private fun socksForm(b: LinearLayout) {
        val listen = field(b, "Listen port  (1–65535, e.g. 1080)", "1080", "1080", numeric = true)
        addButton(b, "Add SOCKS5 proxy") {
            val lp = port(listen.text.toString()) ?: return@addButton toast("Listen port must be 1–65535")
            add(Route(socks = true, listenAddr = "127.0.0.1", listenPort = lp))
        }
    }

    private fun tunnelForm(b: LinearLayout) {
        b.addView(TextView(ctx).apply {
            text = "Exposes this device's adb (127.0.0.1:$ADBD_PORT) to your box, " +
                "encrypted. Run  readb-setup  in the terminal first to enable adb. " +
                "Server port is fixed at $TUNNEL_SERVER_PORT."
            setTextColor(0xFF999999.toInt()); textSize = 11f; setPadding(0, dp(2), 0, dp(4))
        })
        val saddr = field(b, "Remote server IP", "your box ip / host")
        val psk = field(b, "Key  (64 hex chars)", "64 hex characters")
        addButton(b, "Add tunnel") {
            if (saddr.text.isBlank()) return@addButton toast("Server IP required")
            val key = psk.text.toString().trim()
            if (!isHex64(key)) return@addButton toast("Key must be exactly 64 hex characters")
            // Everything else is PINNED: dial the box on the fixed server port,
            // splice to the local adbd on 127.0.0.1:5555.
            add(Route(
                tunnel = true,
                serverAddr = saddr.text.toString().trim(),
                serverPort = TUNNEL_SERVER_PORT,
                targetAddr = "127.0.0.1",
                targetPort = ADBD_PORT,
                psk = key,
            ))
        }
    }

    private fun routesList() {
        val routes = RouteStore.load(ctx)
        if (routes.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "  (none yet)"; setTextColor(0xFF777777.toInt()); setPadding(dp(14), dp(2), 0, dp(8))
            })
            return
        }
        routes.forEach { r ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(8), dp(8))
            }
            row.addView(TextView(ctx).apply {
                text = r.summary(); setTextColor(0xFFDDDDDD.toInt()); textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(ctx).apply {
                text = "Delete"; textSize = 12f
                setOnClickListener {
                    RouteStore.save(ctx, RouteStore.load(ctx).filter { it.id != r.id })
                    onChanged(); render()
                }
            })
            container.addView(row)
        }
    }
}

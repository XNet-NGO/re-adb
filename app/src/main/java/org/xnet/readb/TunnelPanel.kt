package org.xnet.readb

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Static tunnel config UI (no accordions, no dialogs), built in code into a
 * container LinearLayout.
 *
 * Top: the encrypted-tunnel form (add OR edit). Below: the list of saved
 * tunnels, each row with its own Edit / Start|Stop / Delete controls. "enabled"
 * is per-route; [onRestart] re-applies the enabled set by restarting the
 * forwarder service.
 */
class TunnelPanel(
    private val container: LinearLayout,
    private val onChanged: () -> Unit,
    private val onRestart: () -> Unit,
    private val isRunning: () -> Boolean,
) {
    private val ctx: Context = container.context

    /** Non-null when the form is editing an existing route (its id). */
    private var editingId: String? = null

    private companion object {
        const val ADBD_PORT = 5555          // pinned local adbd target
        const val TUNNEL_SERVER_PORT = 2222 // fixed rendezvous-server port
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    
    private fun getColor(colorRes: Int) = ContextCompat.getColor(ctx, colorRes)

    fun render() {
        container.removeAllViews()
        tunnelForm()
        sectionDivider()
        savedTunnelsList()
    }

    // ── View helpers with proper styling ──
    
    private fun sectionHeader(text: String, topMargin: Int = 12) {
        container.addView(TextView(ctx).apply {
            this.text = text
            setTextColor(getColor(R.color.accent_blue))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(topMargin), dp(16), dp(6))
        })
    }

    private fun helpText(text: String, topMargin: Int = 4) {
        container.addView(TextView(ctx).apply {
            this.text = text
            setTextColor(getColor(R.color.text_tertiary))
            textSize = 11f
            setPadding(dp(16), dp(topMargin), dp(16), dp(8))
            setLineSpacing(dp(2).toFloat(), 1.0f)
        })
    }

    private fun sectionDivider() {
        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                dp(1)
            ).apply { 
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
            setBackgroundColor(getColor(R.color.divider_dark))
        })
    }

    private fun fieldLabel(parent: LinearLayout, text: String) {
        parent.addView(TextView(ctx).apply {
            this.text = text
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        })
    }

    private fun editField(parent: LinearLayout, hint: String, value: String = "", 
                         numeric: Boolean = false): EditText {
        return EditText(ctx).apply {
            this.hint = hint
            setText(value)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_hint))
            setBackgroundColor(getColor(R.color.background_input))
            textSize = 14f
            isSingleLine = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            parent.addView(this)
        }
    }

    private fun actionButton(text: String, colorRes: Int, 
                            width: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
                            weight: Float = 0f,
                            onClick: () -> Unit): Button {
        return Button(ctx).apply {
            this.text = text
            textSize = 13f
            setTextColor(getColor(colorRes))
            setBackgroundColor(getColor(R.color.background_tertiary))
            minHeight = dp(42)
            minimumHeight = dp(42)
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (weight > 0f) this.weight = weight
                marginStart = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun smallButton(text: String, colorRes: Int, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            this.text = text
            textSize = 11f
            setTextColor(getColor(colorRes))
            setBackgroundColor(getColor(R.color.background_tertiary))
            minHeight = dp(36)
            minimumHeight = dp(36)
            minWidth = dp(64)
            minimumWidth = dp(64)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            setOnClickListener { onClick() }
        }
    }

    // ── validation helpers ──
    private fun isHex64(s: String) = s.length == 64 && s.all { 
        it.isDigit() || it.lowercaseChar() in 'a'..'f' 
    }
    
    private fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()

    private fun upsert(r: Route) {
        val routes = RouteStore.load(ctx).toMutableList()
        val idx = routes.indexOfFirst { it.id == r.id }
        if (idx >= 0) routes[idx] = r else routes.add(r)
        RouteStore.save(ctx, routes)
        editingId = null
        onChanged()
        render()
    }

    // ── the tunnel form (add or edit) ──
    private fun tunnelForm() {
        val editing = editingId?.let { id -> 
            RouteStore.load(ctx).firstOrNull { it.id == id } 
        }

        // Section header
        if (editing != null) {
            sectionHeader("Edit tunnel", topMargin = 0)
            helpText("Modify the tunnel settings below and tap Save.")
        } else {
            sectionHeader("Add encrypted tunnel", topMargin = 0)
            helpText(
                "Exposes this device's adb (127.0.0.1:$ADBD_PORT) to your remote box via " +
                "an encrypted tunnel. Run readb-setup in the terminal first to enable adb. " +
                "Server port is fixed at $TUNNEL_SERVER_PORT."
            )
        }

        // Form container with padding
        val formContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(12))
        }

        // Server IP field — single-hop ONLY. In multi-hop mode the carrier
        // dest is the FIRST jump hop, so this field is hidden to avoid a
        // redundant/conflicting "server" input.
        val serverGroup = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fieldLabel(serverGroup, "Remote server IP")
        val serverField = editField(
            serverGroup,
            "e.g., 192.168.1.100 or example.com",
            editing?.serverAddr ?: ""
        )
        formContainer.addView(serverGroup)

        // PSK field (shared by single-hop and multi-hop)
        fieldLabel(formContainer, "Encryption key (64 hex characters)")
        val pskField = editField(
            formContainer,
            "64 hexadecimal characters",
            editing?.psk ?: ""
        )

        // ── Multi-hop (jump) mode (portmux v0.5.0) ──
        // A jump route relays the forward stream across one or more relay
        // PEERs before the final target. The first hop MUST equal the remote
        // server above (it is the carrier dest). A local plaintext entry port
        // emits OPENJ; leave the hops blank for an ordinary single-hop tunnel.
        val jumpCheckbox = CheckBox(ctx).apply {
            text = "Multi-hop jump route (relay via peers)"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
            isChecked = editing?.jump ?: false
        }
        formContainer.addView(jumpCheckbox)
        // single-hop server field is irrelevant in multi-hop mode
        serverGroup.visibility = if (jumpCheckbox.isChecked) View.GONE else View.VISIBLE

        // Jump sub-fields (shown/hidden with the checkbox).
        val jumpFields = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (jumpCheckbox.isChecked) View.VISIBLE else View.GONE
        }
        fieldLabel(jumpFields, "Jump hops (comma-separated addr:port, max 8)")
        val hopsField = editField(
            jumpFields,
            "e.g., 10.0.0.1:2222,10.0.0.2:2222",
            editing?.jumpHops?.joinToString(",") ?: ""
        )
        fieldLabel(jumpFields, "Final target (addr:port)")
        val jumpTargetField = editField(
            jumpFields,
            "e.g., 127.0.0.1:5555",
            editing?.let { e ->
                if (e.jump && e.targetPort > 0) "${e.targetAddr}:${e.targetPort}" else ""
            } ?: ""
        )
        fieldLabel(jumpFields, "Local entry (reverse-listen) port")
        val jumpListenField = editField(
            jumpFields,
            "e.g., 6000",
            editing?.jumpListenPort?.takeIf { it > 0 }?.toString() ?: "",
            numeric = true
        )
        formContainer.addView(jumpFields)

        // Echo test checkbox — single-hop ONLY (echo bypasses adb to test the
        // encrypted pipe; it does not apply to a multi-hop jump route).
        val echoCheckbox = CheckBox(ctx).apply {
            text = "Echo test mode (bypass adb, test encryption only)"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
            isChecked = editing?.echo ?: false
            visibility = if (jumpCheckbox.isChecked) View.GONE else View.VISIBLE
        }
        formContainer.addView(echoCheckbox)

        jumpCheckbox.setOnCheckedChangeListener { _, checked ->
            // Multi-hop and single-hop are mutually exclusive: show only the
            // fields each mode uses.
            jumpFields.visibility = if (checked) View.VISIBLE else View.GONE
            serverGroup.visibility = if (checked) View.GONE else View.VISIBLE
            echoCheckbox.visibility = if (checked) View.GONE else View.VISIBLE
            if (checked) {
                // Reveal + bring the newly-shown fields into view above the IME:
                // focusing the first field makes the parent ScrollView scroll it
                // on-screen (and requestRectangleOnScreen covers the keyboard case).
                hopsField.post {
                    hopsField.requestFocus()
                    hopsField.requestRectangleOnScreen(
                        android.graphics.Rect(0, 0, hopsField.width, hopsField.height), false
                    )
                }
            }
        }

        // Action buttons
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }

        if (editing != null) {
            buttonRow.addView(actionButton("Cancel", R.color.text_secondary) {
                editingId = null
                render()
            })
        }

        buttonRow.addView(actionButton(
            if (editing != null) "Save" else "Add tunnel",
            R.color.accent_green
        ) {
            val serverAddr = serverField.text.toString().trim()
            val psk = pskField.text.toString().trim()
            
            if (!jumpCheckbox.isChecked && serverAddr.isBlank()) {
                toast("Server IP is required")
                return@actionButton
            }
            
            if (!isHex64(psk)) {
                toast("Key must be exactly 64 hex characters")
                return@actionButton
            }

            val base = editing ?: Route()

            if (jumpCheckbox.isChecked) {
                // Multi-hop jump route: validate hops + target + entry port.
                val hops = hopsField.text.toString().trim()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (hops.isEmpty() || hops.size > ReadbNative.MAX_HOPS) {
                    toast("Enter 1 to ${ReadbNative.MAX_HOPS} jump hops (addr:port)")
                    return@actionButton
                }
                if (hops.any { it.substringAfterLast(':', "").toIntOrNull() == null }) {
                    toast("Each hop must be addr:port")
                    return@actionButton
                }
                val first = hops.first()
                val firstAddr = first.substringBeforeLast(':')
                val firstPort = first.substringAfterLast(':').toIntOrNull() ?: 0
                val target = jumpTargetField.text.toString().trim()
                val tAddr = target.substringBeforeLast(':', "")
                val tPort = target.substringAfterLast(':', "").toIntOrNull() ?: 0
                val listenPort = jumpListenField.text.toString().trim().toIntOrNull() ?: 0
                if (tAddr.isBlank() || tPort <= 0) {
                    toast("Final target must be addr:port")
                    return@actionButton
                }
                if (listenPort <= 0) {
                    toast("Local entry port is required")
                    return@actionButton
                }
                upsert(base.copy(
                    tunnel = false, socks = false, peer = false,
                    jump = true,
                    jumpHops = hops,
                    jumpListenPort = listenPort,
                    jumpProto = ReadbNative.PROTO_TCP,
                    // First hop is the carrier dest; keep server fields in sync.
                    serverAddr = firstAddr,
                    serverPort = firstPort,
                    targetAddr = tAddr,
                    targetPort = tPort,
                    psk = psk,
                    echo = false,
                ))
                toast(if (editing != null) "Jump route saved" else "Jump route added")
                if (editing != null && editing.enabled && isRunning()) onRestart()
                return@actionButton
            }

            upsert(base.copy(
                tunnel = true,
                jump = false,
                peer = false,
                serverAddr = serverAddr,
                serverPort = TUNNEL_SERVER_PORT,
                targetAddr = "127.0.0.1",
                targetPort = ADBD_PORT,
                psk = psk,
                echo = echoCheckbox.isChecked,
            ))
            
            toast(if (editing != null) "Tunnel saved" else "Tunnel added")
            
            // If editing an enabled route while running, restart to apply changes
            if (editing != null && editing.enabled && isRunning()) {
                onRestart()
            }
        })

        formContainer.addView(buttonRow)
        container.addView(formContainer)
    }

    private fun savedTunnelsList() {
        val routes = RouteStore.load(ctx)
        
        sectionHeader(
            if (editingId != null) "Saved tunnels (editing one above)" 
            else "Saved tunnels"
        )

        if (routes.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "No tunnels configured yet"
                setTextColor(getColor(R.color.text_disabled))
                textSize = 12f
                setPadding(dp(16), dp(8), dp(16), dp(12))
                gravity = Gravity.CENTER
            })
            return
        }

        routes.forEach { route ->
            routeCard(route)
        }
    }

    private fun routeCard(r: Route) {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.background_input))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), dp(4), dp(12), dp(4))
            }
        }

        // Status badge + summary line
        val summaryRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Enabled indicator dot
        summaryRow.addView(TextView(ctx).apply {
            text = if (r.enabled) "●" else "○"
            setTextColor(
                if (r.enabled) getColor(R.color.status_enabled) 
                else getColor(R.color.status_disabled)
            )
            textSize = 16f
            setPadding(0, 0, dp(8), 0)
        })

        // Summary text
        summaryRow.addView(TextView(ctx).apply {
            text = r.summary()
            setTextColor(
                if (r.enabled) getColor(R.color.status_enabled)
                else getColor(R.color.text_secondary)
            )
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        })

        card.addView(summaryRow)

        // Control buttons row
        val controlsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        controlsRow.addView(smallButton("Edit", R.color.accent_blue) {
            editingId = r.id
            render()
        })

        if (r.enabled) {
            controlsRow.addView(smallButton("Stop", R.color.accent_red) {
                setEnabled(r, false)
            })
        } else {
            controlsRow.addView(smallButton("Start", R.color.accent_green) {
                setEnabled(r, true)
            })
        }

        controlsRow.addView(smallButton("Delete", R.color.accent_red_light) {
            RouteStore.save(ctx, RouteStore.load(ctx).filter { it.id != r.id })
            if (editingId == r.id) editingId = null
            onChanged()
            render()
            if (r.enabled && isRunning()) onRestart()
        })

        card.addView(controlsRow)
        container.addView(card)
    }

    /** Flip a route's enabled flag, persist, and apply via the service.
     *  The native engine's routes are fixed at run(), so applying a change
     *  means (re)starting with the new enabled set — handled by [onRestart],
     *  which fully stops if no routes remain enabled. */
    private fun setEnabled(r: Route, enabled: Boolean) {
        val routes = RouteStore.load(ctx).map {
            if (it.id == r.id) it.copy(enabled = enabled) else it
        }
        RouteStore.save(ctx, routes)
        onChanged()
        render()
        onRestart()
    }
}

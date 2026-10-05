package com.agentdeck.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.agentdeck.AppProfiles
import com.agentdeck.DeckState
import com.agentdeck.Fn
import com.agentdeck.R
import com.agentdeck.Screen
import com.agentdeck.XmbMenu
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * MANZANILLA OS v4 — the retro-platformer AI communicator.
 * Sky gradients, pixel clouds and pipes, HUD bar with the orange mascot,
 * workspace cards, live dictation stage, stream deck grid, key legend.
 */
class DeckView(ctx: Context, private val st: DeckState) : View(ctx) {

    private val th get() = Themes.current
    private val dockInfoPane = DockInfoPane()
    private val quickControls = QuickControls()
    var quickControlsOpen = false
    private var quickProgress = 0f
    private var quickFrameAt = 0L
    private val liquidGlass get() = th.name == "LIQUID GLASS"
    private val spiderTheme get() = th.name == "WEB SLINGER"
    private val orbitTheme get() = th.name == "ORBIT"
    private val dockstationTheme get() = th.name == "DOCKSTATION"
    private val auraTheme get() = th.name == "AURA"
    private val fluidGlass get() = liquidGlass || auraTheme
    private val orbitAssets get() = orbitTheme || dockstationTheme

    private val pixelFont: Typeface = try { resources.getFont(R.font.press_start) }
        catch (e: Exception) { Typeface.MONOSPACE }
    private val modernFont = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val modernBold = Typeface.create("sans-serif", Typeface.BOLD)
    // FTT-NewRodin is stored in the supplied PSP firmware as PGF, which the
    // Android Typeface API cannot consume. This native face keeps the same
    // light, wide, low-cost XMB character while remaining GPU text, not images.
    private val dockFont = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val editorialItalic = Typeface.create("serif", Typeface.ITALIC)

    private fun px(c: Int, size: Float) = Paint().apply {
        val modern = fluidGlass || orbitAssets
        val secondaryScale = if (orbitAssets) {
            if (st.screen in listOf(Screen.HOME, Screen.BOOT, Screen.SAVER)) 1.08f else 1.14f
        } else if (st.screen in listOf(Screen.HOME, Screen.BOOT, Screen.SAVER)) 1.08f else 1.24f
        color = c; textSize = if (modern) size * secondaryScale else size
        typeface = if (dockstationTheme) dockFont else if (modern) modernFont else pixelFont
        isAntiAlias = modern
        if (modern) letterSpacing = 0.045f
    }
    private fun mono(c: Int, size: Float) = Paint().apply {
        color = c; textSize = size; typeface = Typeface.MONOSPACE; isAntiAlias = false
    }
    private fun glassHeadline(c: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; textSize = size; typeface = modernBold
    }
    private fun glassEditorial(c: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; textSize = size; typeface = editorialItalic
    }
    private fun dockText(c: Int, size: Float, wide: Boolean = true) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            textSize = size
            typeface = dockFont
            letterSpacing = if (wide) .105f else .035f
            setShadowLayer(3.5f, 0f, 1.5f, Color.argb(165, 0, 0, 0))
        }
    private fun strokePaint(color: Int, width: Float = 2f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val pFill = Paint().apply { isAntiAlias = false }
    private val spritePaint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val orbitCoolEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 105
        colorFilter = PorterDuffColorFilter(Color.rgb(77, 220, 255), PorterDuff.Mode.SRC_IN)
    }
    private val orbitWarmEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 72
        colorFilter = PorterDuffColorFilter(Color.rgb(255, 181, 76), PorterDuff.Mode.SRC_IN)
    }
    private val orbitActualPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mediaCassetteShell by lazy(LazyThreadSafetyMode.NONE) {
        BitmapFactory.decodeResource(resources, R.drawable.media_cassette_shell)
    }
    private val mediaCassetteLabel by lazy(LazyThreadSafetyMode.NONE) {
        BitmapFactory.decodeResource(resources, R.drawable.media_cassette_label)
    }
    private var glassBackdrop: Bitmap? = null
    private var glassBackdropRenderedAt = 0L
    private val refractedBackdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        // Native Canvas equivalent of saturate(155%) brightness(1.05).
        val saturation = ColorMatrix().apply { setSaturation(1.55f) }
        val brightness = ColorMatrix(floatArrayOf(
            1.05f, 0f, 0f, 0f, 0f,
            0f, 1.05f, 0f, 0f, 0f,
            0f, 0f, 1.05f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        saturation.postConcat(brightness)
        colorFilter = ColorMatrixColorFilter(saturation)
        isFilterBitmap = true
    }
    private val coreIcons = BitmapFactory.decodeResource(resources, R.drawable.manzanilla_core_icons)
    private val appIcons = BitmapFactory.decodeResource(resources, R.drawable.manzanilla_app_icons)
    private val webIcons = BitmapFactory.decodeResource(resources, R.drawable.manzanilla_web_icons)
    private val actionIcons = BitmapFactory.decodeResource(resources, R.drawable.manzanilla_action_icons)
    private var gestureOnStatus = false
    private var dockCardLastIndex = -1
    private var dockCardMovedAt = 0L
    private val orbitCoreIcons = BitmapFactory.decodeResource(resources, R.drawable.manzanilla_orbit_core_icons)
    private val orbitAppIcons = BitmapFactory.decodeResource(resources, R.drawable.manzanilla_orbit_app_icons)
    private val orbitGamesIcon = BitmapFactory.decodeResource(resources, R.drawable.manzanilla_orbit_games)
    private val orbitThemesIcon = BitmapFactory.decodeResource(resources, R.drawable.manzanilla_orbit_themes)
    private val orbitMoreIcon = BitmapFactory.decodeResource(resources, R.drawable.manzanilla_orbit_more)
    // The original artwork is the light-surface set. Dark-surface versions are
    // derived once, preserving brand hues while lifting dark outlines/details.
    private val coreIconsDark by lazy(LazyThreadSafetyMode.NONE) { darkSurfaceVariant(coreIcons) }
    private val appIconsDark by lazy(LazyThreadSafetyMode.NONE) { darkSurfaceVariant(appIcons) }
    private val webIconsDark by lazy(LazyThreadSafetyMode.NONE) { darkSurfaceVariant(webIcons) }
    private val actionIconsDark by lazy(LazyThreadSafetyMode.NONE) { darkSurfaceVariant(actionIcons) }
    private val brandFlower = BitmapFactory.decodeResource(resources, R.drawable.manzanilla_flower_generated)
    private val brandFlowerSrc = Rect(179, 130, 1075, 1126)
    private fun saverBitmap(id: Int, alpha: Boolean = true): Bitmap =
        BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply {
            inPreferredConfig = if (alpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        })
    // The saver layers and avatar atlases are among the largest assets in the
    // application. Load them only when their screen is actually opened so the
    // launcher remains light and responsive on the Gama 800-series hardware.
    private val saverLandscape by lazy(LazyThreadSafetyMode.NONE) {
        saverBitmap(R.drawable.saver_landscape_day, false)
    }
    private val saverClouds by lazy(LazyThreadSafetyMode.NONE) { saverBitmap(R.drawable.saver_clouds) }
    private val saverTreeBare by lazy(LazyThreadSafetyMode.NONE) { saverBitmap(R.drawable.saver_tree_bare) }
    private val saverFoliage by lazy(LazyThreadSafetyMode.NONE) { saverBitmap(R.drawable.saver_foliage_full) }
    private val saverFoliageWind by lazy(LazyThreadSafetyMode.NONE) { saverBitmap(R.drawable.saver_foliage_wind) }
    private val saverReflections by lazy(LazyThreadSafetyMode.NONE) {
        listOf(
            R.drawable.saver_reflection_0, R.drawable.saver_reflection_1,
            R.drawable.saver_reflection_2, R.drawable.saver_reflection_3,
            R.drawable.saver_reflection_4, R.drawable.saver_reflection_5,
            R.drawable.saver_reflection_6, R.drawable.saver_reflection_7,
            R.drawable.saver_reflection_8, R.drawable.saver_reflection_9
        ).map { saverBitmap(it) }
    }
    private val iconBoundsCache = HashMap<String, Rect>()
    private val petFace = PetFaceRenderer()
    private val webPet by lazy(LazyThreadSafetyMode.NONE) { WebPetRenderer(ctx) }
    private val suhairPet by lazy(LazyThreadSafetyMode.NONE) { AtlasPetRenderer(ctx) }

    private val coreIconMap = mapOf(
        "chatgpt" to 0, "codex" to 1, "claude" to 2, "wispr" to 3,
        "stream" to 4, "spotify" to 5, "chrome" to 6, "whatsapp" to 7,
        "files" to 8, "tasks" to 9, "agents" to 10, "settings" to 11
    )
    private val appIconMap = mapOf(
        "notion" to 0, "comet" to 1, "discord" to 2, "slack" to 3,
        "youtube" to 4, "gmail" to 5, "zoom" to 6, "figma" to 7,
        "vscode" to 8, "excel" to 9, "word" to 10, "camera" to 11
    )
    private val webIconMap = mapOf(
        "new_tab" to 0, "address" to 1, "url_meet" to 2,
        "url_outlook" to 3, "url_teams" to 4, "url_youtube" to 5,
        "url_instagram" to 6, "url_whatsapp" to 7, "url_chatgpt" to 8
    )
    private val actionIconMap = mapOf(
        "new" to 0, "search" to 1, "back" to 2, "forward" to 3, "reload" to 4, "focus" to 5,
        "next" to 6, "previous" to 7, "play" to 8, "stop" to 9, "send" to 10, "talk" to 11,
        "copy" to 12, "paste" to 13, "undo" to 14, "redo" to 15, "accept" to 16, "reject" to 17,
        "mute" to 18, "volup" to 19, "voldown" to 20, "video" to 21, "share" to 22, "close" to 23
    )

    var learnIndex = 0
    var voiceLine = ""
    var voiceMode = "agent"
    var liveStatus = "Ready"
    var livePreview = false
    var liveCompanionReady = false
    var liveCompanionFailed = false
    private val liveVoiceScene = LiveVoiceScene()
    var liveTranscriptPhase = ""
    var voiceReady = false
    var voiceCapturing = false
    var saverVideoFrame: Bitmap? = null
    var cameraPreviewFrame: Bitmap? = null
    var bootHandoffFrame: Bitmap? = null
    var onTap: ((String) -> Unit)? = null
    var onLongPress: ((String) -> Unit)? = null
    var onSlider: ((String, Float) -> Unit)? = null
    var onBootDone: (() -> Unit)? = null
    private var bootDoneFired = false
    private var featureBootActive = false
    private val frame get() = System.currentTimeMillis()
    private val blink get() = (frame / 400) % 2 == 0L

    init {
        StoryBootScene.setFont(pixelFont)
        FeatureBootScene.setFont(pixelFont)
        FeatureBootScene.setBrandFlower(brandFlower, brandFlowerSrc)
    }

    fun prepareVideoBoot() {
        bootDoneFired = false
        featureBootActive = false
        shownScreen = Screen.BOOT
        transStart = frame
        invalidate()
    }

    fun startFeatureBoot(frameBitmap: Bitmap?) {
        bootHandoffFrame?.takeIf { it !== frameBitmap && !it.isRecycled }?.recycle()
        bootHandoffFrame = frameBitmap
        st.bootStart = frame
        bootDoneFired = false
        featureBootActive = true
        shownScreen = Screen.BOOT
        transStart = frame
        invalidate()
    }

    fun cancelBootVisuals() {
        featureBootActive = false
        bootDoneFired = true
    }

    private var shownScreen = Screen.BOOT
    private var transStart = 0L
    private val hitZones = ArrayList<Pair<RectF, String>>()
    private var pressedZone: String? = null
    private var pressedBounds: RectF? = null
    private var pressedX = 0f
    private var pressedY = 0f
    private var longPressFired = false
    private var orbitShownPage = st.homePage
    private var orbitFromPage = st.homePage
    private var orbitPageStartedAt = 0L
    private var orbitLastSelection = st.homeSel
    private var orbitFocusStartedAt = 0L
    private var auraLastSelection = st.homeSel
    private var auraFocusStartedAt = 0L
    private var auraShownPage = st.homePage
    private var auraFromPage = st.homePage
    private var auraPageStartedAt = 0L
    private var xmbLastCategory = st.xmbCategory
    private var xmbLastItem = st.xmbItem
    private var xmbMovedAt = 0L
    private var orbitLastSettingsCategory = st.settingsCategory
    private var orbitLastSettingsItem = st.settingsItem
    private var orbitSettingsFocusStartedAt = 0L
    private var dockStatusKind = ""
    private var dockStatusChangedAt = 0L
    private val longPressTask = Runnable {
        val id = pressedZone
        if (id != null && id.startsWith("tile:")) {
            longPressFired = true
            onLongPress?.invoke(id)
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val quickNow = frame
        val quickDt = if (quickFrameAt == 0L) .033f else ((quickNow-quickFrameAt)/1000f).coerceIn(0f,.1f)
        quickFrameAt = quickNow
        val quickTarget = if (quickControlsOpen) 1f else 0f
        quickProgress += (quickTarget-quickProgress)*(1f-exp(-quickDt*15f))
        if (abs(quickTarget-quickProgress)<.001f) quickProgress=quickTarget
        c.drawColor(Color.rgb(15,33,54))
        c.save()
        if (quickProgress>0f) {
            c.translate(0f,-70f*quickProgress)
            c.scale(1f-.035f*quickProgress,1f-.035f*quickProgress,width/2f,0f)
        }
        val liveSurface = st.screen == Screen.VOICE && voiceMode == "live"
        if (st.screen != Screen.BOOT && !liveSurface) drawBackdrop(c)
        if (st.screen != shownScreen) {
            val previous = shownScreen
            shownScreen = st.screen
            if (liveSurface) liveVoiceScene.reset()
            // The boot scene has already animated into the home layout. A
            // second page-slide here would undo that seamless handoff.
            transStart = if (previous == Screen.BOOT && st.screen == Screen.HOME)
                frame - 240L else frame
        }
        val tt = ((frame - transStart) / 240f).coerceIn(0f, 1f)
        val ease = 1 - (1 - tt) * (1 - tt) * (1 - tt)
        // BOOT owns its camera movement. Applying normal page navigation here
        // made the first coded frame slide in from the right after the video.
        val glassOverlay = fluidGlass && st.screen in listOf(
            Screen.AI_WORKSPACE, Screen.APP_CONTROLLER, Screen.VOLUME)
        val shapeOverlay = st.screen == Screen.DYNAMIC_STATUS
        val dx = if (st.screen == Screen.BOOT || glassOverlay || shapeOverlay || liveSurface) 0f else
            (1 - ease) * width * if (st.screen == Screen.HOME) -1 else 1

        hitZones.clear()
        c.save()
        c.translate(dx, 0f)
        when (st.screen) {
            Screen.BOOT -> {
                val bootT = frame - st.bootStart
                if (!featureBootActive) {
                    c.drawColor(Color.BLACK)
                } else {
                    val homeP = DockstationBootScene.homeProgress(bootT)
                    if (homeP > 0f) {
                        val homeLayer = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(),
                            (homeP * 255f).toInt().coerceIn(0, 255))
                        drawBackdrop(c, forceHomeGround = true)
                        drawHome(c)
                        drawHud(c)
                        drawLegend(c)
                        c.restoreToCount(homeLayer)
                    }
                    val sceneFade = if (homeP < 0.72f) 1f else
                        (1f - (homeP - 0.72f) / 0.28f).coerceIn(0f, 1f)
                    if (sceneFade > 0f) {
                        if (sceneFade >= 0.999f) {
                            drawOrbitBackdrop(c)
                            DockstationBootScene.render(c, width, height, bootT, brandFlower, brandFlowerSrc)
                        } else {
                            val sceneLayer = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(),
                                (sceneFade * 255f).toInt().coerceIn(0, 255))
                            drawOrbitBackdrop(c)
                            DockstationBootScene.render(c, width, height, bootT, brandFlower, brandFlowerSrc)
                            c.restoreToCount(sceneLayer)
                        }
                    }
                }
                if (featureBootActive && bootT >= DockstationBootScene.TOTAL && !bootDoneFired) {
                    bootDoneFired = true
                    onBootDone?.invoke()
                }
                // Home is rendered as a visual target during the morph, but it
                // must not become interactive until boot completion.
                hitZones.clear()
            }
            Screen.HOME -> drawHome(c)
            Screen.DASHBOARD -> if (st.page == 1) drawFiles(c) else drawDashboard(c)
            Screen.AGENT -> drawAgent(c)
            Screen.VOICE -> drawVoice(c)
            Screen.SETTINGS -> drawSettings(c)
            Screen.KEYLEARN -> drawKeyLearn(c)
            Screen.SPAWN_PICK -> drawSpawnPick(c)
            Screen.VIEWFINDER -> drawCamera(c)
            Screen.SAVER -> drawSaver(c)
            Screen.DIAL -> drawDial(c)
            Screen.STORE -> drawStore(c)
            Screen.THEMES -> drawThemes(c)
            Screen.STREAM -> drawStream(c)
            Screen.APP_SWITCHER -> drawAppSwitcher(c)
            Screen.APP_CONTROLLER -> drawAppController(c)
            Screen.AI_WORKSPACE -> drawAiWorkspace(c)
            Screen.TASKS -> drawTasks(c)
            Screen.VOLUME -> drawVolume(c)
            Screen.SAVER_PICK -> drawSaverPick(c)
            Screen.GAMES -> drawGames(c)
            Screen.PET -> when (st.selectedPet) {
                "webby" -> webPet.draw(c, width, height, st, frame, ::zone)
                "suhair" -> suhairPet.draw(c, width, height, st, frame, ::zone)
                else -> petFace.draw(c, width, height, st, frame, ::zone)
            }
            Screen.PET_PICK -> drawPetPicker(c)
            Screen.PET_CALIBRATE -> drawPetCalibration(c)
            Screen.PET_GAZE_CALIBRATE -> drawPetGazeCalibration(c)
            Screen.DYNAMIC_STATUS -> drawDynamicStatus(c)
        }
        c.restore()
        if (st.screen !in listOf(Screen.BOOT, Screen.SAVER, Screen.PET, Screen.PET_CALIBRATE,
                Screen.PET_GAZE_CALIBRATE, Screen.APP_SWITCHER, Screen.DYNAMIC_STATUS) &&
            !glassOverlay && !liveSurface) drawHud(c)
        if (st.screen in listOf(Screen.HOME, Screen.DASHBOARD, Screen.AGENT,
            Screen.TASKS, Screen.STREAM, Screen.APP_CONTROLLER) &&
            !(dockstationTheme && st.screen == Screen.STREAM)) drawLegend(c)
        // boot→home handoff: the chamomile flies from center stage to its
        // permanent seat in the header — the animation becomes the OS
        val introP = if (st.homeIntroStart > 0)
            ((frame - st.homeIntroStart) / 750f).coerceIn(0f, 1f) else 1f
        if (introP < 1f && st.screen == Screen.HOME) {
            val e = 1 - (1 - introP) * (1 - introP) * (1 - introP)
            val fx = width / 2f - 36f + (16f - (width / 2f - 36f)) * e
            val fy = height * 0.30f + (9f - height * 0.30f) * e
            val sc = 6f + (2.4f - 6f) * e
            spritePaint.alpha = if (introP > 0.9f) ((1 - introP) * 2550).toInt().coerceIn(0, 255) else 255
            c.drawBitmap(Sprites.chamomile, null,
                RectF(fx, fy, fx + Sprites.chamomile.width * sc, fy + Sprites.chamomile.height * sc), spritePaint)
            spritePaint.alpha = 255
        }
        if (st.screen != Screen.BOOT) drawToast(c)
        c.restore()
        if (quickProgress>0f) {
            // The drawer exclusively owns input; the receding screen cannot receive a stray tap.
            hitZones.clear()
            pFill.color=Color.argb((85*quickProgress).toInt(),6,18,32)
            c.drawRect(0f,0f,width.toFloat(),height.toFloat(),pFill)
            c.save()
            c.translate(0f,(1f-quickProgress)*height)
            quickControls.draw(c,width.toFloat(),height.toFloat(),st,quickNow,::zone)
            c.restore()
            // Hit rectangles are authored at rest; disable them while the sheet is travelling.
            if (quickProgress<.99f || !quickControlsOpen) hitZones.clear()
        } else if (dockstationTheme && st.screen == Screen.HOME) {
            pFill.color=Color.argb(150,219,241,255)
            c.drawRoundRect(RectF(width/2f-23f,height-4f,width/2f+23f,height-1f),2f,2f,pFill)
            zone(RectF(width/2f-70f,height-13f,width/2f+70f,height.toFloat()),"quick_open")
        }
        // The artwork is authored for a deliberate 20 FPS pixel cadence.
        // This keeps motion lively without burning power at the panel's full refresh rate.
        // The separate 3D view owns its frame loop; native labels refresh quietly.
        if (liveSurface) postInvalidateDelayed(100L)
        else if (abs(quickTarget-quickProgress)>.001f) postInvalidateOnAnimation()
        else postInvalidateDelayed(if (orbitAssets && st.orbitSmoothMotion) 33L else 50L)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Hit-test frontmost controls first, matching canvas draw order.
                // Otherwise the whole-pane background swallows its buttons.
                val hit = hitZones.lastOrNull { it.first.contains(ev.x, ev.y) }
                pressedZone = hit?.second ?: "anywhere"
                pressedBounds = hit?.first?.let { RectF(it) }
                pressedX = ev.x
                pressedY = ev.y
                gestureOnStatus = (dockstationTheme || auraTheme) &&
                    (st.screen == Screen.HOME && dockStatusBounds().contains(ev.x, ev.y) ||
                        st.screen == Screen.DYNAMIC_STATUS)
                longPressFired = false
                if (pressedZone?.startsWith("slider:") == true)
                    updateSlider(pressedZone!!, pressedBounds, ev.x)
                removeCallbacks(longPressTask)
                postDelayed(longPressTask, 520L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressedZone?.startsWith("slider:") == true) {
                    updateSlider(pressedZone!!, pressedBounds, ev.x)
                    return true
                }
                if (abs(ev.x - pressedX) > 18f || abs(ev.y - pressedY) > 18f) {
                    removeCallbacks(longPressTask)
                    pressedZone = null
                    pressedBounds = null
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressTask)
                val id = pressedZone
                val swipeX = ev.x - pressedX
                val swipeY = ev.y - pressedY
                if (id?.startsWith("slider:") == true) updateSlider(id, pressedBounds, ev.x)
                pressedZone = null
                pressedBounds = null
                if (!longPressFired && quickControlsOpen && swipeY>65f && abs(swipeY)>abs(swipeX)*1.2f) {
                    onTap?.invoke("quick_close")
                } else if (!longPressFired && !quickControlsOpen &&
                    swipeY < -65f && abs(swipeY)>abs(swipeX)*1.2f &&
                    st.screen !in listOf(Screen.BOOT,Screen.VOICE,Screen.KEYLEARN)) {
                    onTap?.invoke("quick_open")
                } else if (!longPressFired && !quickControlsOpen && gestureOnStatus && abs(swipeX) > 54f &&
                    abs(swipeX) > abs(swipeY) * 1.15f) {
                    onTap?.invoke(if (swipeX < 0f) "status_swipe_next" else "status_swipe_prev")
                } else if (!longPressFired && orbitTheme && st.screen == Screen.HOME &&
                    abs(swipeX) > 62f && abs(swipeX) > abs(swipeY) * 1.2f) {
                    onTap?.invoke("home_page_toggle")
                } else if (!longPressFired && id != null && !id.startsWith("slider:")) {
                    onTap?.invoke(id)
                }
                longPressFired = false
                gestureOnStatus = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressTask)
                pressedZone = null
                pressedBounds = null
                longPressFired = false
                gestureOnStatus = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun updateSlider(id: String, bounds: RectF?, x: Float) {
        val r = bounds ?: return
        onSlider?.invoke(id.removePrefix("slider:"), ((x - r.left) / r.width()).coerceIn(0f, 1f))
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressTask)
        glassBackdrop?.takeIf { !it.isRecycled }?.recycle()
        glassBackdrop = null
        super.onDetachedFromWindow()
    }

    private fun zone(r: RectF, id: String) = hitZones.add(r to id)

    private fun dockStatusBounds() = if (auraTheme)
        RectF(18f, 68f, width * .59f, 278f)
    else RectF(12f, 12f, width - 12f, 250f)

    // ---- primitives ---------------------------------------------------------

    private fun sprite(c: Canvas, b: Bitmap, x: Float, y: Float, scale: Float) =
        c.drawBitmap(b, null, RectF(x, y, x + b.width * scale, y + b.height * scale), spritePaint)

    private fun drawSheetIcon(c: Canvas, sheet: Bitmap, index: Int, cols: Int, rows: Int, dest: RectF): Boolean {
        if (index < 0 || index >= cols * rows) return false
        val cw = sheet.width / cols
        val ch = sheet.height / rows
        val sx = (index % cols) * cw
        val sy = (index / cols) * ch
        val cacheKey = "${System.identityHashCode(sheet)}:$index:$cols:$rows"
        val src = iconBoundsCache.getOrPut(cacheKey) {
            var left = sx + cw
            var top = sy + ch
            var right = sx
            var bottom = sy
            for (yy in sy until sy + ch) for (xx in sx until sx + cw) {
                if (Color.alpha(sheet.getPixel(xx, yy)) > 24) {
                    left = minOf(left, xx); top = minOf(top, yy)
                    right = maxOf(right, xx + 1); bottom = maxOf(bottom, yy + 1)
                }
            }
            if (right <= left || bottom <= top) Rect(sx, sy, sx + cw, sy + ch)
            else Rect(left, top, right, bottom)
        }
        val scale = minOf(dest.width() / src.width(), dest.height() / src.height())
        val dw = src.width() * scale
        val dh = src.height() * scale
        val fitted = RectF(dest.centerX() - dw / 2f, dest.centerY() - dh / 2f,
            dest.centerX() + dw / 2f, dest.centerY() + dh / 2f)
        if (orbitAssets && (sheet === orbitCoreIcons || sheet === orbitAppIcons ||
                sheet === orbitGamesIcon || sheet === orbitThemesIcon || sheet === orbitMoreIcon)) {
            drawOrbitOpticalIcon(c, sheet, src, fitted)
        } else {
            c.drawBitmap(sheet, src, fitted, spritePaint)
        }
        return true
    }

    /**
     * Runtime optical edge engine for generated ORBIT icons. The art supplies
     * only shape and material; light direction, rim separation and the moving
     * specular band are calculated here so focus never looks pre-baked.
     */
    private fun drawOrbitOpticalIcon(c: Canvas, sheet: Bitmap, src: Rect, dest: RectF) {
        val lightPhase = frame / 1900.0
        val lx = cos(lightPhase).toFloat() * 1.7f
        val ly = sin(lightPhase * .73).toFloat() * 1.4f
        c.drawBitmap(sheet, src, RectF(dest.left + 2.2f - lx, dest.top + 1.6f - ly,
            dest.right + 2.2f - lx, dest.bottom + 1.6f - ly), orbitCoolEdgePaint)
        c.drawBitmap(sheet, src, RectF(dest.left - 1.8f + lx, dest.top - 1.2f + ly,
            dest.right - 1.8f + lx, dest.bottom - 1.2f + ly), orbitWarmEdgePaint)
        c.drawBitmap(sheet, src, dest, orbitActualPaint)

        // The moving light is expressed by the two phase-shifted spectral
        // edges above. Avoiding a separate off-screen alpha layer per icon is
        // important on this device's older GPU and keeps page motion fluid.
    }

    /** Build the second icon set for dark cards without flattening brand colours. */
    private fun darkSurfaceVariant(source: Bitmap): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val alpha = Color.alpha(color)
            if (alpha == 0) continue
            var r = Color.red(color)
            var g = Color.green(color)
            var b = Color.blue(color)
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val chroma = max - min
            val luma = (r * 54 + g * 183 + b * 19) / 256
            when {
                // Neutral black/navy outlines become a cool luminous outline,
                // remaining distinct from cream and white interior details.
                luma < 82 && chroma < 58 -> {
                    r = 104; g = 174; b = 229
                }
                luma < 118 && chroma < 44 -> {
                    r = 145; g = 198; b = 236
                }
                // Very dark brand colours keep their hue but gain enough value
                // to remain visible on Midnight, Game Kid and similar cards.
                luma < 92 -> {
                    val scale = 164f / max.coerceAtLeast(1)
                    r = (r * scale).toInt().coerceIn(28, 255)
                    g = (g * scale).toInt().coerceIn(28, 255)
                    b = (b * scale).toInt().coerceIn(28, 255)
                }
            }
            pixels[i] = Color.argb(alpha, r, g, b)
        }
        out.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return out
    }

    private fun themedSheet(light: Bitmap, dark: Bitmap): Bitmap =
        if (th.darkIconSurface) dark else light

    /** Opaque card-colour strip painted after the icon, keeping labels above art. */
    private fun labelStrip(c: Canvas, r: RectF, height: Float) {
        pFill.color = when {
            liquidGlass -> Color.argb(86, 253, 252, 246)
            orbitAssets -> Color.argb(142, 5, 20, 42)
            else -> th.panel
        }
        pFill.alpha = if (liquidGlass || orbitAssets) 255 else 248
        if (liquidGlass || orbitAssets) {
            pFill.isAntiAlias = true
            c.drawRoundRect(RectF(r.left + 5f, r.bottom - height, r.right - 5f, r.bottom - 5f),
                11f, 11f, pFill)
            pFill.isAntiAlias = false
        } else {
            c.drawRect(r.left + 4f, r.bottom - height, r.right - 4f, r.bottom - 4f, pFill)
        }
        pFill.alpha = 255
    }

    private fun normalizedIconId(raw: String): String {
        val s = raw.lowercase()
        return when {
            "chatgpt" in s -> "chatgpt"
            "codex" in s -> "codex"
            "claude" in s || "clor" in s -> "claude"
            "wispr" in s -> "wispr"
            "stream" in s -> "stream"
            "spotify" in s -> "spotify"
            "creative cloud" in s || "adobe" in s -> "creative_cloud"
            "whatsapp" in s -> "whatsapp"
            "comet" in s || "perplexity" in s -> "comet"
            "chrome" in s -> "chrome"
            "notion" in s -> "notion"
            "discord" in s -> "discord"
            "slack" in s -> "slack"
            "youtube" in s -> "youtube"
            "gmail" in s -> "gmail"
            "zoom" in s -> "zoom"
            "figma" in s -> "figma"
            "visual studio" in s || "vscode" in s -> "vscode"
            "excel" in s -> "excel"
            "word" in s -> "word"
            "camera" in s || "screenshot" in s || "capture" in s -> "camera"
            "file" in s || "explorer" in s -> "files"
            "task" in s -> "tasks"
            "agent" in s -> "agents"
            "setting" in s -> "settings"
            else -> s.trim()
        }
    }

    private fun drawGeneratedIcon(c: Canvas, raw: String, dest: RectF): Boolean {
        val id = normalizedIconId(raw)
        if (spiderTheme) {
            SpiderIcons.draw(c, id, dest, frame)
            return true
        }
        if (dockstationTheme && DockIcons.draw(c, id, dest, frame)) return true
        if (orbitAssets) when (id) {
            "pet" -> return drawSheetIcon(c, orbitCoreIcons, 10, 4, 3, dest)
            "games" -> return drawSheetIcon(c, orbitGamesIcon, 0, 1, 1, dest)
            "themes" -> return drawSheetIcon(c, orbitThemesIcon, 0, 1, 1, dest)
            "more" -> return drawSheetIcon(c, orbitMoreIcon, 0, 1, 1, dest)
        }
        if (id == "creative_cloud") {
            drawCreativeCloudIcon(c, dest)
            return true
        }
        coreIconMap[id]?.let {
            val sheet = if (orbitAssets) orbitCoreIcons else themedSheet(coreIcons, coreIconsDark)
            return drawSheetIcon(c, sheet, it, 4, 3, dest)
        }
        appIconMap[id]?.let {
            val sheet = if (orbitAssets) orbitAppIcons else themedSheet(appIcons, appIconsDark)
            return drawSheetIcon(c, sheet, it, 4, 3, dest)
        }
        return false
    }

    /**
     * Code-native Creative Cloud mark. It stays sharp at the device's small
     * resolution and can participate in the same focus/physics animation as
     * the other Stream Deck icons without carrying a fixed bitmap background.
     */
    private fun drawCreativeCloudIcon(c: Canvas, dest: RectF) {
        val size = minOf(dest.width(), dest.height())
        val cx = dest.centerX()
        val cy = dest.centerY()
        val red = Color.rgb(238, 45, 62)
        val coral = Color.rgb(255, 104, 70)
        val glow = strokePaint(Color.argb(70, 255, 92, 78), size * .16f)
        val outer = strokePaint(red, size * .105f)
        val inner = strokePaint(coral, size * .078f)
        val left = RectF(cx - size * .38f, cy - size * .26f, cx + size * .05f, cy + size * .25f)
        val right = RectF(cx - size * .05f, cy - size * .26f, cx + size * .38f, cy + size * .25f)
        c.drawArc(left, 36f, 288f, false, glow)
        c.drawArc(right, 216f, 288f, false, glow)
        c.drawArc(left, 36f, 288f, false, outer)
        c.drawArc(right, 216f, 288f, false, outer)
        c.drawArc(RectF(cx - size * .23f, cy - size * .14f, cx + size * .23f, cy + size * .20f),
            195f, 150f, false, inner)
        val shine = strokePaint(Color.argb(210, 255, 240, 225), size * .022f)
        c.drawArc(left, 210f, 67f, false, shine)
    }

    private fun actionId(raw: String): String {
        val s = raw.lowercase().replace('_', ' ')
        return when {
            "new" in s || "group" in s -> "new"
            "search" in s || "address" in s || "prompt" in s || "quick" in s || "home" in s -> "search"
            "back" in s || "up folder" in s -> "back"
            "forward" in s -> "forward"
            "reload" in s || "refresh" in s || "reopen" in s -> "reload"
            "next" in s -> "next"
            "previous" in s || "prev" in s -> "previous"
            "play" in s -> "play"
            "stop" in s || "escape" in s -> "stop"
            "send" in s -> "send"
            "talk" in s -> "talk"
            "copy" in s -> "copy"
            "paste" in s -> "paste"
            "undo" in s -> "undo"
            "redo" in s -> "redo"
            "accept" in s || "approve" in s -> "accept"
            "reject" in s -> "reject"
            "mute" in s || "deafen" in s -> "mute"
            "volume +" in s || "volup" in s -> "volup"
            "volume -" in s || "voldown" in s -> "voldown"
            "video" in s -> "video"
            "share" in s -> "share"
            "close" in s -> "close"
            "focus" in s || "open" in s || "thread" in s || "people" in s ||
                "gallery" in s || "raise hand" in s || "like" in s || "archive" in s || "chat" in s -> "focus"
            else -> s
        }
    }

    private fun drawActionIcon(c: Canvas, raw: String, dest: RectF): Boolean {
        val index = actionIconMap[actionId(raw)] ?: return false
        return drawSheetIcon(c, themedSheet(actionIcons, actionIconsDark), index, 6, 4, dest)
    }

    private fun center(c: Canvas, text: String, p: Paint, y: Float) =
        c.drawText(text, (width - p.measureText(text)) / 2f, y, p)

    private fun fitText(raw: String, p: Paint, maxWidth: Float): String {
        val clean = raw.replace('\n', ' ').trim()
        if (p.measureText(clean) <= maxWidth) return clean
        var cut = clean
        while (cut.length > 3 && p.measureText("$cut...") > maxWidth) cut = cut.dropLast(1)
        return "$cut..."
    }

    /** navy panel with light border — the v4 card */
    private fun panel(c: Canvas, r: RectF, border: Int = th.panelLine, thick: Float = 4f) {
        if (auraTheme) {
            drawAuraPanel(c, r, border)
            return
        }
        if (fluidGlass) {
            drawLiquidGlassPanel(c, r, border, thick)
            return
        }
        if (orbitAssets) {
            drawOrbitPanel(c, r, border, thick)
            return
        }
        if (spiderTheme) {
            drawSpiderPanel(c, r, border, thick)
            return
        }
        pFill.color = th.panel; pFill.alpha = 235
        c.drawRect(r, pFill)
        pFill.alpha = 255
        pFill.color = border
        c.drawRect(r.left, r.top, r.right, r.top + thick, pFill)
        c.drawRect(r.left, r.bottom - thick, r.right, r.bottom, pFill)
        c.drawRect(r.left, r.top, r.left + thick, r.bottom, pFill)
        c.drawRect(r.right - thick, r.top, r.right, r.bottom, pFill)
    }

    private fun drawAuraPanel(c: Canvas, r: RectF, tint: Int) {
        val radius = minOf(34f, r.height() * .34f)
        pFill.isAntiAlias = true
        for (i in 2 downTo 1) {
            pFill.color = Color.argb(7 + i * 3, 19, 27, 40)
            val spread = i * 2.4f
            c.drawRoundRect(RectF(r.left - spread, r.top + 5f - spread * .3f,
                r.right + spread, r.bottom + 8f + spread), radius + spread, radius + spread, pFill)
        }
        val clip = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
        c.save(); c.clipPath(clip)
        glassBackdrop?.takeIf { !it.isRecycled }?.let { backdrop ->
            val inset = minOf(10f, minOf(r.width(), r.height()) * .08f)
            val src = Rect((r.left + inset).toInt().coerceAtLeast(0),
                (r.top + inset).toInt().coerceAtLeast(0),
                (r.right - inset).toInt().coerceAtMost(backdrop.width),
                (r.bottom - inset).toInt().coerceAtMost(backdrop.height))
            if (src.width() > 0 && src.height() > 0) c.drawBitmap(backdrop, src, r, refractedBackdropPaint)
        }
        pFill.color = Color.argb(112, 248, 250, 252); c.drawRoundRect(r, radius, radius, pFill)
        val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(r.right, r.bottom, maxOf(80f, r.width()*.72f),
                Color.argb(31, Color.red(tint), Color.green(tint), Color.blue(tint)),
                Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawRect(r, wash)
        val top = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(r.left, r.top, r.left, r.top + r.height()*.52f,
                Color.argb(84,255,255,255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawRect(r, top); c.restore()
        c.drawRoundRect(r, radius, radius, strokePaint(Color.argb(78,255,255,255), 1.05f))
        c.drawArc(RectF(r.left+3f,r.top+2f,r.right-3f,r.top+radius*1.75f),
            198f,143f,false,strokePaint(Color.argb(152,255,255,255),1.2f))
        pFill.isAntiAlias = false
    }

    private fun drawAuraDarkPanel(c: Canvas, r: RectF) {
        val radius = minOf(42f, r.height()*.22f)
        pFill.isAntiAlias = true
        for (i in 3 downTo 1) {
            pFill.color = Color.argb(7+i*4, 7, 14, 25)
            val s=i*2.8f
            c.drawRoundRect(RectF(r.left-s,r.top+7f-s*.25f,r.right+s,r.bottom+11f+s),radius+s,radius+s,pFill)
        }
        val clip=Path().apply{addRoundRect(r,radius,radius,Path.Direction.CW)}
        c.save(); c.clipPath(clip)
        val base=Paint(Paint.ANTI_ALIAS_FLAG).apply{shader=LinearGradient(r.left,r.top,r.right,r.bottom,
            intArrayOf(Color.argb(226,26,34,50),Color.argb(218,27,44,61),Color.argb(224,24,29,43)),
            null,Shader.TileMode.CLAMP)}
        c.drawRect(r,base)
        val cyanGlow=Paint(Paint.ANTI_ALIAS_FLAG).apply{shader=RadialGradient(r.left+r.width()*.38f,r.bottom+8f,r.width()*.68f,
            Color.argb(185,37,190,213),Color.TRANSPARENT,Shader.TileMode.CLAMP)}
        c.drawRect(r,cyanGlow)
        val violetGlow=Paint(Paint.ANTI_ALIAS_FLAG).apply{shader=RadialGradient(r.right+16f,r.centerY(),r.width()*.52f,
            Color.argb(102,214,77,180),Color.TRANSPARENT,Shader.TileMode.CLAMP)}
        c.drawRect(r,violetGlow)
        val sheen=Paint(Paint.ANTI_ALIAS_FLAG).apply{shader=LinearGradient(r.left,r.top,r.left,r.bottom,
            Color.argb(61,255,255,255),Color.TRANSPARENT,Shader.TileMode.CLAMP)}
        c.drawRect(r,sheen); c.restore()
        c.drawRoundRect(r,radius,radius,strokePaint(Color.argb(102,255,255,255),1.15f))
        pFill.isAntiAlias=false
    }

    private fun drawOrbitPanel(c: Canvas, r: RectF, border: Int, thick: Float) {
        val radius = minOf(24f, r.height() * .28f)
        pFill.isAntiAlias = true
        pFill.color = Color.argb(62, 0, 4, 16)
        c.drawRoundRect(RectF(r.left + 2f, r.top + 5f, r.right + 2f, r.bottom + 8f),
            radius + 2f, radius + 2f, pFill)
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf(Color.argb(218, 30, 72, 108), Color.argb(188, 8, 30, 62)),
                null, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(r, radius, radius, body)
        c.drawRoundRect(r, radius, radius,
            strokePaint(if (border == th.panelLine) Color.argb(132, 190, 232, 255) else border,
                thick.coerceIn(1.2f, 4f)))
        c.drawArc(RectF(r.left + 4f, r.top + 3f, r.right - 4f, r.top + 19f),
            190f, 160f, false, strokePaint(Color.argb(126, 255, 255, 255), 1.4f))
        pFill.isAntiAlias = false
    }

    private fun drawSpiderPanel(c: Canvas, r: RectF, border: Int, thick: Float) {
        pFill.isAntiAlias = true
        pFill.color = Color.argb(224, 6, 18, 47)
        c.drawRoundRect(r, 12f, 12f, pFill)
        c.drawRoundRect(r, 12f, 12f, strokePaint(border, thick.coerceAtMost(4f)))
        val web = strokePaint(Color.argb(120, 126, 176, 255), 1.2f)
        val ox = r.right - 5f; val oy = r.top + 5f
        for (a in listOf(115.0, 140.0, 165.0)) {
            c.drawLine(ox, oy, ox + cos(Math.toRadians(a)).toFloat() * 24f,
                oy + sin(Math.toRadians(a)).toFloat() * 24f, web)
        }
        c.drawArc(RectF(ox - 25f, oy - 25f, ox + 13f, oy + 13f), 90f, 95f, false, web)
        pFill.isAntiAlias = false
    }

    private fun drawLiquidGlassPanel(c: Canvas, r: RectF, border: Int, thick: Float) {
        val radius = if (auraTheme) minOf(31f, r.height() * 0.38f)
            else minOf(22f, r.height() * 0.24f)
        val selected = border == th.yellow || border == th.green || border == th.accent ||
            border == th.cyan || border == th.fg

        // Exact material recipe translated from LIQUID-GLASS-GUIDE.md:
        // 1.55 saturation, 1.05 brightness, 26px rim bend, a 14% -> 5% -> 2%
        // white ramp, 55% rim, a dark lower wall and warm/cool fringes.
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(if (selected) 46 else 34, 20, 30, 40)
        }
        c.drawRoundRect(RectF(r.left + 1.5f, r.top + 5f, r.right + 1.5f, r.bottom + 8f),
            radius + 1f, radius + 1f, shadow)

        val clip = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)

        // Sample the wallpaper behind the material. The middle is gently
        // magnified; each outer wall samples up to 26px inward so straight
        // wallpaper features visibly bend at the glass rim.
        glassBackdrop?.takeIf { !it.isRecycled }?.let { backdrop ->
            val opticalInset = minOf(6.5f, minOf(r.width(), r.height()) * 0.075f)
            val bodySrc = Rect(
                (r.left + opticalInset).coerceAtLeast(0f).toInt(),
                (r.top + opticalInset).coerceAtLeast(0f).toInt(),
                (r.right - opticalInset).coerceAtMost(backdrop.width.toFloat()).toInt(),
                (r.bottom - opticalInset).coerceAtMost(backdrop.height.toFloat()).toInt()
            )
            if (bodySrc.right > bodySrc.left && bodySrc.bottom > bodySrc.top)
                c.drawBitmap(backdrop, bodySrc, r, refractedBackdropPaint)

            // The full 26px wall lens is reserved for the active/focused
            // surface. This preserves the optical signature while keeping the
            // 854x480 device comfortably responsive.
            if (border == th.yellow || border == th.fg) {
                val wall = minOf(8f, minOf(r.width(), r.height()) * 0.13f)
                val displacement = minOf(26f, minOf(r.width(), r.height()) * 0.32f)
                fun sample(src: RectF, dst: RectF) {
                    val safe = Rect(
                        src.left.coerceIn(0f, backdrop.width - 1f).toInt(),
                        src.top.coerceIn(0f, backdrop.height - 1f).toInt(),
                        src.right.coerceIn(1f, backdrop.width.toFloat()).toInt(),
                        src.bottom.coerceIn(1f, backdrop.height.toFloat()).toInt()
                    )
                    if (safe.right > safe.left && safe.bottom > safe.top)
                        c.drawBitmap(backdrop, safe, dst, refractedBackdropPaint)
                }
                sample(RectF(r.left + displacement, r.top, r.left + displacement + wall, r.bottom),
                    RectF(r.left, r.top, r.left + wall, r.bottom))
                sample(RectF(r.right - displacement - wall, r.top, r.right - displacement, r.bottom),
                    RectF(r.right - wall, r.top, r.right, r.bottom))
                sample(RectF(r.left, r.top + displacement, r.right, r.top + displacement + wall),
                    RectF(r.left, r.top, r.right, r.top + wall))
                sample(RectF(r.left, r.bottom - displacement - wall, r.right, r.bottom - displacement),
                    RectF(r.left, r.bottom - wall, r.right, r.bottom))
            }
        }

        // Selected glass receives only a low-alpha tint beneath the sheen.
        val tintRgb = if (selected) intArrayOf(Color.red(border), Color.green(border), Color.blue(border))
            else intArrayOf(255, 255, 255)
        val tintAlpha = if (selected) (if (auraTheme) 43 else 31) else if (auraTheme) 18 else 9
        val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(r.left, r.top, r.left, r.bottom,
                Color.argb(tintAlpha, tintRgb[0], tintRgb[1], tintRgb[2]),
                Color.argb((tintAlpha * 0.55f).toInt(), tintRgb[0], tintRgb[1], tintRgb[2]),
                Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(r, radius, radius, tintPaint)

        // Production white sheen ramp: 0.14 -> 0.05 -> 0.02.
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(r.left, r.top, r.left, r.bottom,
                intArrayOf(Color.argb(36, 255, 255, 255), Color.argb(13, 255, 255, 255),
                    Color.argb(5, 255, 255, 255)),
                floatArrayOf(0f, 0.47f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(r, radius, radius, body)

        // Shared living specular: every surface reflects the same slow-moving light.
        val globalLight = 0.2f + 0.6f * ((sin(frame / 2800.0) + 1.0) / 2.0).toFloat()
        val lightX = r.left + r.width() * globalLight
        val specular = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(lightX, r.top + 2f, maxOf(42f, r.width() * 0.25f),
                Color.argb(101, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawRect(r, specular)
        c.restore()

        // Bright rim and curved wall thickness.
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1.2f, thick * 0.45f)
            color = if (selected) Color.argb(178, Color.red(border), Color.green(border), Color.blue(border))
                else Color.argb(140, 255, 255, 255)
        }
        c.drawRoundRect(r, radius, radius, rim)
        val innerWall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.argb(26, 255, 255, 255)
        }
        c.drawRoundRect(RectF(r.left + 1.2f, r.top + 1.2f, r.right - 1.2f, r.bottom - 1.2f),
            maxOf(1f, radius - 1.2f), maxOf(1f, radius - 1.2f), innerWall)
        val topLip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(r.left, r.top, r.right, r.top,
                intArrayOf(Color.argb(235, 255, 255, 255), Color.argb(51, 255, 214, 130),
                    Color.argb(36, 120, 170, 255)), null, Shader.TileMode.CLAMP)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        c.drawArc(RectF(r.left + 2f, r.top + 2f, r.right - 2f, r.top + radius * 2.1f),
            194f, 152f, false, topLip)
        val lowerLip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(if (auraTheme) 28 else 46, 15, 25, 35)
            style = Paint.Style.STROKE
            strokeWidth = minOf(5f, maxOf(2.2f, r.height() * 0.065f))
        }
        c.drawArc(RectF(r.left + 2f, r.bottom - radius * 2.1f, r.right - 2f, r.bottom - 2f),
            14f, 152f, false, lowerLip)
    }

    private fun dashedBox(c: Canvas, r: RectF, col: Int, thick: Float = 4f) {
        pFill.color = col
        val seg = 14f; val gap = 9f
        var x = r.left
        while (x < r.right) {
            c.drawRect(x, r.top, minOf(x + seg, r.right), r.top + thick, pFill)
            c.drawRect(x, r.bottom - thick, minOf(x + seg, r.right), r.bottom, pFill)
            x += seg + gap
        }
        var y = r.top
        while (y < r.bottom) {
            c.drawRect(r.left, y, r.left + thick, minOf(y + seg, r.bottom), pFill)
            c.drawRect(r.right - thick, y, r.right, minOf(y + seg, r.bottom), pFill)
            y += seg + gap
        }
    }

    private fun mascotFor(kind: String): Bitmap? = when (kind) {
        "claude" -> Sprites.flower; "codex" -> Sprites.book
        "wispr" -> Sprites.wisprbox; "cowork" -> Sprites.orange
        else -> null
    }

    private fun agentName(kind: String) = when (kind) {
        "claude" -> "CLAUDE"; "codex" -> "CODEX"; "cowork" -> "COWORK"; else -> "" }

    private fun stateLabel(s: String) = when (s) {
        "working" -> "WORKING"; "done" -> "DONE!"; "needs_input" -> "NEEDS YOU"
        "error" -> "ERROR"; "paused" -> "ZZZ"; "idle" -> "IDLE"; else -> "" }

    private fun stateColor(s: String) = when (s) {
        "working" -> th.green; "done" -> th.cyan; "needs_input" -> th.yellow
        "error" -> th.accent; "paused" -> th.gray; else -> th.gray }

    // ---- backdrop: sky, clouds, bricks, pipes -------------------------------

    private fun drawBackdrop(c: Canvas, forceHomeGround: Boolean = false) {
        if (orbitAssets) {
            drawOrbitBackdrop(c)
        } else if (fluidGlass) {
            if (width <= 0 || height <= 0) return
            var backdrop = glassBackdrop
            if (backdrop == null || backdrop.width != width || backdrop.height != height || backdrop.isRecycled) {
                backdrop?.takeIf { !it.isRecycled }?.recycle()
                backdrop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                glassBackdrop = backdrop
                glassBackdropRenderedAt = 0L
            }
            if (frame - glassBackdropRenderedAt >= 100L) {
                val backdropCanvas = Canvas(backdrop)
                backdropCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                if (auraTheme) drawAuraBackdrop(backdropCanvas)
                else drawLiquidGlassBackdrop(backdropCanvas)
                glassBackdropRenderedAt = frame
            }
            spritePaint.isFilterBitmap = true
            c.drawBitmap(backdrop, 0f, 0f, spritePaint)
            spritePaint.isFilterBitmap = false
        } else if (spiderTheme) {
            drawSpiderBackdrop(c)
        } else if (th.sky) {
            val g = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
                    th.bg, th.bg2, Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), g)
            // drifting clouds
            val drift = ((frame / 120) % (width + 300)).toFloat()
            sprite(c, Sprites.cloud, width - drift, height * 0.16f, 5f)
            sprite(c, Sprites.cloud, width - drift * 0.6f - 300, height * 0.30f, 4f)
            if (st.screen != Screen.BOOT || forceHomeGround) {
                // ground strip: bricks with pipes at the edges
                val bs = 5f
                val bh = Sprites.brick.height * bs
                var x = 0f
                while (x < width) { sprite(c, Sprites.brick, x, height - bh, bs); x += Sprites.brick.width * bs }
                sprite(c, Sprites.pipe, 6f, height - bh - Sprites.pipe.height * 5f, 5f)
                sprite(c, Sprites.pipe, width - 56f, height - bh - Sprites.pipe.height * 5f, 5f)
                sprite(c, Sprites.qblock, 64f, height - bh - 46f, 5f)
                sprite(c, Sprites.star, width - 118f, height - bh - 50f, 5f)
            }
        } else {
            c.drawColor(th.bg)
            for (i in 0 until 30) {
                val sx = ((i * 733) % width).toFloat()
                val sy = ((i * 397) % height).toFloat()
                val tw = (sin(frame / 400.0 + i * 2.1) * 0.5 + 0.5).toFloat()
                pFill.color = th.cyan; pFill.alpha = (140 * tw).toInt()
                c.drawRect(sx, sy, sx + 3f, sy + 3f, pFill)
            }
            pFill.alpha = 255
        }
    }

    private fun drawOrbitBackdrop(c: Canvas) {
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
                intArrayOf(Color.rgb(15, 50, 112), Color.rgb(18, 106, 159), Color.rgb(15, 86, 150)),
                floatArrayOf(0f, .58f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)

        // Low-cost procedural counterpart to the XMB wave field.  Each strand
        // shares the same travelling phase but has its own amplitude and
        // wavelength, producing the characteristic slow, fluid interference.
        val phase = frame / 5200.0
        for (i in 0 until 8) {
            val path = Path()
            val baseY = 286f + (i - 3.5f) * 12f
            for (x in -24..(width + 24) step 8) {
                val xf = x.toFloat()
                val y = baseY +
                    sin(xf / (112.0 + i * 7.0) + phase + i * .41).toFloat() * (29f + i * 2f) +
                    sin(xf / 248.0 - phase * .72 + i).toFloat() * 18f
                if (x == -24) path.moveTo(xf, y) else path.lineTo(xf, y)
            }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when (i % 3) {
                    0 -> Color.argb(76, 226, 250, 255)
                    1 -> Color.argb(60, 118, 216, 255)
                    else -> Color.argb(50, 198, 171, 255)
                }
                style = Paint.Style.STROKE
                strokeWidth = if (i == 3 || i == 4) 2.2f else 1.15f
            }
            c.drawPath(path, line)
        }

        val hazeTop = height * .42f
        val haze = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, hazeTop, 0f, height.toFloat(),
                Color.TRANSPARENT, Color.argb(42, 186, 229, 255), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, hazeTop, width.toFloat(), height.toFloat(), haze)
    }

    /**
     * ORBIT's coded continuation begins on the movie's exact last frame, then
     * turns that locked signal into the same cross-media bar used by the live
     * launcher.  The feature demo is therefore a working UI, not a second set
     * of unrelated presentation slides.
     */
    private fun renderOrbitFeatureBoot(c: Canvas, t: Long, handoff: Bitmap?) {
        val signal = (((t - 120L) / 720f).coerceIn(0f, 1f)).let { p ->
            p * p * (3f - 2f * p)
        }
        drawOrbitBackdrop(c)

        val originalCategory = st.xmbCategory
        val originalItem = st.xmbItem
        val stage = when (t) {
            in 0L..1_650L -> Triple(0, 0, "AI COMMUNICATOR")
            in 1_651L..2_720L -> Triple(2, 1, "VOICE CONTROL")
            in 2_721L..3_820L -> Triple(0, 3, "LIVE TASK STATUS")
            in 3_821L..5_050L -> Triple(2, 0, "CONTROL YOUR PC")
            else -> Triple(originalCategory, originalItem, "SYSTEM ONLINE")
        }
        st.xmbCategory = stage.first
        st.xmbItem = stage.second
        val xmbLayer = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(),
            (signal * 255f).toInt().coerceIn(0, 255))
        drawOrbitHome(c)
        if (t > 820L) {
            val heading = xmbText(Color.WHITE, 19f, true)
            val sub = xmbText(Color.argb(215, 215, 241, 255), 10f, false)
            c.drawText(stage.third, width - 298f, 94f, heading)
            c.drawText(when (stage.third) {
                "AI COMMUNICATOR" -> "TALK TO YOUR AI"
                "VOICE CONTROL" -> "SPEAK THROUGH MANZANILLA"
                "LIVE TASK STATUS" -> "SEE WHAT IS WORKING"
                "CONTROL YOUR PC" -> "STREAM DECK MODE"
                else -> "MANZANILLA IS READY"
            }, width - 298f, 112f, sub)
        }
        c.restoreToCount(xmbLayer)
        st.xmbCategory = originalCategory
        st.xmbItem = originalItem

        // Preserve the decoded film frame during the lock, with no black gap.
        if (handoff != null && !handoff.isRecycled && signal < 1f) {
            spritePaint.alpha = ((1f - signal) * 255f).toInt().coerceIn(0, 255)
            c.drawBitmap(handoff, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), spritePaint)
            spritePaint.alpha = 255
        }

        // The generated flower is a single continuous object: centered over
        // the film handoff, then docked beside the ORBIT wordmark.
        val dock = (((t - 520L) / 820f).coerceIn(0f, 1f)).let { p ->
            1f - (1f - p) * (1f - p) * (1f - p)
        }
        val start = RectF(width / 2f - 67f, height / 2f - 72f,
            width / 2f + 67f, height / 2f + 72f)
        val end = RectF(11f, 8f, 41f, 40f)
        val flower = RectF(
            start.left + (end.left - start.left) * dock,
            start.top + (end.top - start.top) * dock,
            start.right + (end.right - start.right) * dock,
            start.bottom + (end.bottom - start.bottom) * dock
        )
        if (t < 1_520L) {
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(((1f - dock) * 95f).toInt(), 255, 218, 94)
                maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawOval(flower, glow)
        }
        c.drawBitmap(brandFlower, brandFlowerSrc, flower, orbitActualPaint)
        if (dock > .72f) c.drawText("MANZANILLA", 49f, 31f, xmbText(Color.WHITE, 12f, true))

        // Restrained CRT scanlines disappear as the digital interface settles.
        if (t < 1_500L) {
            pFill.color = Color.argb(((1f - signal) * 42f).toInt(), 0, 0, 0)
            for (y in 0 until height step 4) c.drawRect(0f, y.toFloat(), width.toFloat(), y + 1f, pFill)
        }
    }

    private fun drawSpiderBackdrop(c: Canvas) {
        val sky = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
                intArrayOf(Color.rgb(4, 11, 30), Color.rgb(13, 38, 82), Color.rgb(32, 24, 64)),
                null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), sky)
        val moonX = width - 92f + sin(frame / 15000.0).toFloat() * 4f
        pFill.isAntiAlias = true; pFill.color = Color.rgb(225, 232, 244)
        c.drawCircle(moonX, 83f, 39f, pFill)
        for (i in 0 until 18) {
            val bw = 42f + (i % 4) * 11f
            val x = i * 53f - ((frame / 900L) % 53L).toFloat() * 0.15f
            val top = 258f - (i * 37 % 105)
            pFill.color = if (i % 3 == 0) Color.rgb(8, 18, 42) else Color.rgb(10, 25, 55)
            c.drawRect(x, top, x + bw, height.toFloat(), pFill)
            for (wy in (top.toInt() + 14) until height step 19) {
                for (wx in (x.toInt() + 9) until (x + bw - 5).toInt() step 15) {
                    pFill.color = if ((wx + wy + i) % 5 == 0) Color.rgb(240, 190, 86)
                        else Color.rgb(36, 72, 117)
                    c.drawRect(wx.toFloat(), wy.toFloat(), wx + 5f, wy + 7f, pFill)
                }
            }
        }
        val web = strokePaint(Color.argb(72, 166, 205, 255), 1.5f)
        c.save(); c.rotate(((frame / 70f) % 360f) * 0.035f, 40f, 58f)
        for (i in 0 until 7) {
            val a = i * Math.PI * 2 / 7
            c.drawLine(40f, 58f, 40f + cos(a).toFloat() * 105f,
                58f + sin(a).toFloat() * 105f, web)
        }
        for (rad in listOf(32f, 58f, 84f, 106f)) c.drawCircle(40f, 58f, rad, web)
        c.restore(); pFill.isAntiAlias = false
    }

    private fun drawLiquidGlassBackdrop(c: Canvas) {
        // Pale maple basketball court from the approved visual reference.
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(Color.rgb(255, 247, 227), Color.rgb(248, 229, 193),
                    Color.rgb(255, 242, 214)), null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)

        // Subtle horizontal grain: deterministic and cheap enough for the
        // low-power panel, but materially richer than a flat beige fill.
        val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(18, 126, 82, 35)
            style = Paint.Style.STROKE
            strokeWidth = 0.7f
        }
        for (i in 0 until 44) {
            val y = i * (height / 43f) + sin(i * 1.71).toFloat() * 1.8f
            c.drawLine(0f, y, width.toFloat(), y + sin(i * 0.83).toFloat() * 1.2f, grain)
        }

        // Court circles glide independently in slow 22–31 second loops. The
        // motion is visible through the glass and makes the refraction legible.
        val redX = -34f + sin(frame / 11000.0).toFloat() * 34f
        val redY = 255f + cos(frame / 14500.0).toFloat() * 22f
        val yellowX = 528f + cos(frame / 15500.0).toFloat() * 42f
        val yellowY = 398f + sin(frame / 12000.0).toFloat() * 25f
        val redCourt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 170, 21, 27)
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
        }
        val yellowCourt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(178, 241, 171, 0)
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
        }
        c.drawCircle(redX, redY, 246f, redCourt)
        c.drawCircle(yellowX, yellowY, 223f, yellowCourt)
        c.drawLine(0f, 150f + sin(frame / 13000.0).toFloat() * 10f,
            width.toFloat(), 360f + cos(frame / 12000.0).toFloat() * 12f, yellowCourt)

        // Slowly travelling ball and a restrained motion streak.
        val ballX = width - 72f + sin(frame / 9000.0).toFloat() * 28f
        val ballY = 79f + cos(frame / 11500.0).toFloat() * 13f
        val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(ballX - 155f, ballY, ballX - 24f, ballY,
                Color.TRANSPARENT, Color.argb(78, 235, 98, 35), Shader.TileMode.CLAMP)
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
        }
        c.drawLine(ballX - 150f, ballY + 17f, ballX - 28f, ballY + 2f, trail)
        pFill.isAntiAlias = true
        pFill.color = Color.argb(54, 45, 28, 14)
        c.drawOval(RectF(ballX - 24f, ballY + 27f, ballX + 33f, ballY + 39f), pFill)
        val ball = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(ballX - 9f, ballY - 12f, 39f,
                Color.rgb(255, 151, 54), Color.rgb(204, 75, 15), Shader.TileMode.CLAMP)
        }
        c.drawCircle(ballX, ballY, 31f, ball)
        val seam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 62, 34, 20)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        c.drawArc(RectF(ballX - 31f, ballY - 10f, ballX + 31f, ballY + 10f),
            180f, 180f, false, seam)
        c.drawArc(RectF(ballX - 11f, ballY - 31f, ballX + 11f, ballY + 31f),
            88f, 184f, false, seam)
        c.drawLine(ballX - 25f, ballY - 18f, ballX + 24f, ballY + 19f, seam)
        pFill.isAntiAlias = false
    }

    /**
     * AURA's wallpaper is rendered at the device's native size. Large colour
     * lights move behind a pale diffusion layer, making the panel refraction
     * visible without video decoding, network access, or large assets.
     */
    private fun drawAuraBackdrop(c: Canvas) {
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(Color.rgb(245, 248, 251), Color.rgb(234, 242, 247),
                    Color.rgb(250, 246, 245)), floatArrayOf(0f, .56f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)

        fun lightBall(cx: Float, cy: Float, radius: Float, inner: Int, middle: Int) {
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, radius,
                    intArrayOf(inner, middle, Color.TRANSPARENT),
                    floatArrayOf(0f, .43f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawCircle(cx, cy, radius, glow)
            val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx - radius * .18f, cy - radius * .21f, radius * .62f,
                    Color.argb(86, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            }
            c.drawCircle(cx, cy, radius * .74f, core)
        }

        val slow = frame / 11000.0
        c.save(); c.scale(.46f, 1f, width*.34f, height*.56f)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(width*.34f,
            height*.56f, 285f, Color.argb(214,8,14,23), Color.TRANSPARENT, Shader.TileMode.CLAMP) }
        c.drawCircle(width*.34f, height*.56f, 285f, shadow); c.restore()
        lightBall(width * .19f + sin(slow).toFloat() * 25f,
            height * .67f + cos(slow * .73).toFloat() * 18f, 235f,
            Color.argb(142, 46, 209, 232), Color.argb(70, 38, 154, 208))
        lightBall(width * .73f + cos(slow * .81).toFloat() * 31f,
            height * .35f + sin(slow * .67).toFloat() * 22f, 250f,
            Color.argb(108, 220, 83, 195), Color.argb(55, 125, 83, 214))
        lightBall(width * .82f + sin(slow * .54 + 1.8).toFloat() * 28f,
            height * .84f + cos(slow * .61).toFloat() * 16f, 235f,
            Color.argb(126, 255, 162, 73), Color.argb(65, 255, 90, 90))

        // Frosted diffusion and subtle physical grain keep the wallpaper
        // bright while preventing colour fields from competing with text.
        pFill.color = Color.argb(118, 250, 252, 253)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
        pFill.color = Color.argb(11, 55, 72, 88)
        for (y in 1 until height step 4) {
            val offset = ((y * 37) % 7).toFloat()
            for (x in offset.toInt() until width step 13) c.drawCircle(x.toFloat(), y.toFloat(), .48f, pFill)
        }
    }

    // ---- HUD + legend -------------------------------------------------------

    private fun drawHud(c: Canvas) {
        val introP = if (st.homeIntroStart > 0)
            ((frame - st.homeIntroStart) / 750f).coerceIn(0f, 1f) else 1f
        val hudDy = -(1 - introP) * (1 - introP) * 60f
        c.save()
        c.translate(0f, hudDy)
        if (auraTheme) {
            drawAuraHud(c)
            c.restore()
            return
        }
        if (dockstationTheme) {
            // Dockstation carries its identity inside the info pane beside the
            // clock. Keep only a quiet keypad Back affordance on sub-screens.
            if (st.screen != Screen.HOME) {
                val back = dockText(Color.WHITE, 12f)
                c.drawText("0   BACK", 22f, 34f, back)
                zone(RectF(8f, 3f, 138f, 56f), "go_back")
            }
            c.restore()
            return
        }
        if (orbitTheme) {
            val cal = Calendar.getInstance()
            val time = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            c.drawBitmap(brandFlower, brandFlowerSrc, RectF(11f, 8f, 41f, 40f), orbitActualPaint)
            c.drawText("MANZANILLA", 49f, 31f, xmbText(Color.WHITE, 12f, true))
            c.drawText(time, width - 121f, 31f, xmbText(Color.WHITE, 15f, false))
            pFill.color = if (st.connected) Color.rgb(96, 230, 177) else Color.rgb(255, 167, 89)
            c.drawCircle(width - 28f, 25f, 4f, pFill)
            val bridge = xmbText(Color.argb(205, 255, 255, 255), 9f, false)
            val bridgeText = if (st.connected) "BRIDGE" else "OFFLINE"
            c.drawText(bridgeText, width - 40f - bridge.measureText(bridgeText), 29f, bridge)
            if (st.screen != Screen.HOME) {
                val back = xmbText(Color.WHITE, 11f, true)
                c.drawText("0  BACK", 22f, height - 19f, back)
                zone(RectF(7f, height - 54f, 125f, height - 3f), "go_back")
            } else zone(RectF(8f, 3f, 150f, 46f), "replay_boot")
            c.restore()
            return
        }
        if (st.screen !in listOf(Screen.HOME, Screen.BOOT, Screen.SAVER)) {
            val back = if (liquidGlass) RectF(18f, 18f, 144f, 65f)
                else RectF(12f, height - 60f, 114f, height - 25f)
            panel(c, back, if (liquidGlass) th.panelLine else th.accent, 2f)
            val backLabel = if (liquidGlass) glassHeadline(th.fg, 9.5f) else px(th.fg, 10f)
            val text = "‹  BACK"
            c.drawText(text, back.centerX() - backLabel.measureText(text) / 2f,
                back.centerY() + 4f, backLabel)
            zone(back, "go_back")
        }
        val r = if (liquidGlass)
            RectF(width / 2f - 262f, 18f, width / 2f + 262f, 65f)
        else RectF(6f, 5f, width - 6f, 52f)
        panel(c, r)
        if (liquidGlass) {
            val cy = r.centerY()
            pFill.isAntiAlias = true
            pFill.color = th.accent
            c.drawCircle(r.left + 27f, cy, 14f, pFill)
            pFill.color = Color.rgb(255, 247, 230)
            for (i in 0 until 8) {
                val a = i * Math.PI * 2 / 8
                c.drawCircle(r.left + 27f + cos(a).toFloat() * 7f,
                    cy + sin(a).toFloat() * 7f, 3.2f, pFill)
            }
            pFill.color = th.yellow
            c.drawCircle(r.left + 27f, cy, 3.4f, pFill)
            pFill.isAntiAlias = false
            zone(RectF(r.left + 8f, r.top + 5f, r.left + 48f, r.bottom - 5f), "replay_boot")

            c.drawText("MANZANILLA OS", r.left + 54f, cy + 5f, glassHeadline(th.fg, 11f))
            val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(38, 48, 48, 48)
                strokeWidth = 1f
            }
            c.drawLine(r.left + 166f, r.top + 11f, r.left + 166f, r.bottom - 11f, divider)

            val cal = Calendar.getInstance()
            val time = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            c.drawText(time, r.left + 184f, cy + 5f, glassHeadline(th.fg, 11f))

            val ok = st.connected
            pFill.isAntiAlias = true
            pFill.color = th.green
            c.drawCircle(r.left + 269f, cy - 1f, 4f, pFill)
            pFill.isAntiAlias = false
            c.drawText(if (st.nowKind.isNotBlank()) "AI ACTIVE" else "AI READY",
                r.left + 280f, cy + 5f, px(th.green, 9f))

            // Wi-Fi glyph.
            val wifi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = th.fg; style = Paint.Style.STROKE; strokeWidth = 2f
            }
            c.drawArc(RectF(r.left + 356f, cy - 9f, r.left + 376f, cy + 9f), 220f, 100f, false, wifi)
            c.drawArc(RectF(r.left + 361f, cy - 4f, r.left + 371f, cy + 6f), 220f, 100f, false, wifi)
            pFill.color = th.fg
            c.drawCircle(r.left + 366f, cy + 5f, 1.7f, pFill)

            // Dynamic Android battery percentage.
            val battery = try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
            } catch (_: Exception) { 78 }
            val batteryX = r.left + 397f
            val batteryOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = th.fg; style = Paint.Style.STROKE; strokeWidth = 1.2f
            }
            c.drawRoundRect(RectF(batteryX, cy - 7f, batteryX + 20f, cy + 6f), 2f, 2f, batteryOutline)
            pFill.color = if (battery < 20) th.accent else th.green
            c.drawRoundRect(RectF(batteryX + 2f, cy - 5f,
                batteryX + 2f + 16f * battery / 100f, cy + 4f), 1f, 1f, pFill)
            c.drawText("$battery%", batteryX + 26f, cy + 5f, px(th.fg, 9f))
            c.drawLine(r.right - 81f, r.top + 11f, r.right - 81f, r.bottom - 11f, divider)
            c.drawText(if (ok) "BRIDGE OK" else "OFFLINE", r.right - 69f, cy + 5f,
                px(if (ok) th.green else th.accent, 9f))
            c.restore()
            return
        }

        spritePaint.alpha = 255
        if (spiderTheme) SpiderIcons.draw(c, "claude", RectF(12f, 5f, 56f, 49f), frame)
        else c.drawBitmap(brandFlower, brandFlowerSrc, RectF(16f, 8f, 51f, 49f), spritePaint)
        zone(RectF(0f, 0f, 340f, 52f), "replay_boot")
        c.drawText("MANZANILLA OS", 62f, 38f, px(th.fg, 19f))
        // NOW context chip
        if (st.nowName.isNotEmpty()) {
            val p = px(th.yellow, 13f)
            c.drawText("NOW:${st.nowName}${if (st.nowPinned) "*" else ""}", 350f, 35f, p)
        }
        // signal bars
        val sx = width - 250f
        for (i in 0 until 4) {
            pFill.color = if (st.connected) th.green else th.gray
            c.drawRect(sx + i * 10, 38f - i * 6f, sx + i * 10 + 7, 42f, pFill)
        }
        val ok = st.connected
        val p = px(if (ok) th.green else th.accent, 15f)
        val label = if (ok) "BRIDGE OK" else if (blink) "NO BRIDGE" else ""
        if (label.isNotEmpty()) c.drawText(label, width - 195f, 36f, p)
        sprite(c, Sprites.coin, width - 46f, 12f, 3.4f)
        c.restore()
    }

    private fun drawAuraHud(c: Canvas) {
        val cal = Calendar.getInstance()
        val time = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        drawAuraDotText(c, time, 24f, 20f, 1.35f, th.fg)

        val context = when {
            st.pcNotificationTitle.isNotBlank() -> "NEW ${st.pcNotificationApp.ifBlank { "UPDATE" }.uppercase()}"
            st.mediaActive -> "NOW PLAYING"
            st.nowState == "working" -> "AI WORKING"
            else -> "READY"
        }
        val contextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(178,30,38,50); textSize = 11.5f; typeface = modernFont; letterSpacing = .04f
        }
        c.drawText(context, 111f, 38f, contextPaint)

        val bridgeColor = if (st.connected) th.green else th.accent
        pFill.color = bridgeColor
        c.drawCircle(width - 174f, 31f, 4f, pFill)
        val bridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = th.fg; textSize = 10.5f; typeface = modernFont
        }
        val bridge = if (st.connected) "BRIDGE" else "OFFLINE"
        c.drawText(bridge, width - 164f, 35f, bridgePaint)

        val page = if (st.screen == Screen.HOME) "${st.homePage + 1}/2" else "0  BACK"
        val pageR = RectF(width - 73f, 10f, width - 17f, 50f)
        pFill.isAntiAlias = true; pFill.color = Color.argb(218, 33, 37, 44)
        c.drawRoundRect(pageR, 20f, 20f, pFill)
        val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = if (st.screen == Screen.HOME) 14f else 10f; typeface = modernFont
        }
        c.drawText(page, pageR.centerX() - pagePaint.measureText(page) / 2f,
            pageR.centerY() + 5f, pagePaint)
        zone(pageR, if (st.screen == Screen.HOME) "home_page_toggle" else "go_back")
        pFill.isAntiAlias = false
    }

    private fun drawLegend(c: Canvas) {
        if (auraTheme) {
            val hint = if (st.screen == Screen.HOME)
                "1–8 OPEN   ·   9 / − / + PAGE   ·   0 SETTINGS"
            else "2/8 MOVE   ·   5 SELECT   ·   0 BACK"
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(205, 39, 48, 62)
                textSize = 11.5f
                typeface = modernBold
                letterSpacing = .035f
            }
            c.drawText(hint, width / 2f - paint.measureText(hint) / 2f, height - 12f, paint)
            return
        }
        if (orbitTheme) {
            val text = "4/6  CATEGORY     2/8  ITEM     5  ENTER     0  BACK"
            val paint = xmbText(Color.argb(215, 255, 255, 255), 10f, false)
            c.drawText(text, width / 2f - paint.measureText(text) / 2f, height - 14f, paint)
            return
        }
        if (dockstationTheme) {
            if (st.screen == Screen.HOME) {
                val arcButton = RectF(width - 113f, height - 43f, width - 10f, height - 7f)
                pFill.color = Color.argb(210, 20, 55, 72)
                c.drawRoundRect(arcButton, 18f, 18f, pFill)
                val label = dockText(Color.rgb(130, 245, 218), 14f)
                c.drawText("*  PC APP", arcButton.centerX() - label.measureText("*  PC APP") / 2f, arcButton.centerY() + 5f, label)
                zone(arcButton, "arc_open")
            }
            val parts = listOf(
                Color.rgb(104, 234, 191) to "CALL", Color.WHITE to "  TALK",
                Color.rgb(255, 121, 91) to "     END", Color.WHITE to "  STOP",
                Color.rgb(103, 210, 255) to "     MUTE", Color.WHITE to "  DICTATE",
                Color.rgb(255, 215, 107) to "     0", Color.WHITE to "  BACK"
            )
            var total = 0f
            val measure = dockText(Color.WHITE, 11f)
            parts.forEach { total += measure.measureText(it.second) }
            var x = (width - total) / 2f
            for ((color, text) in parts) {
                val p = dockText(color, 11f)
                c.drawText(text, x, height - 13f, p)
                x += p.measureText(text)
            }
            return
        }
        if (liquidGlass) {
            val text = "HOLD A TILE FOR CONTROLS"
            val tp = px(Color.rgb(116, 90, 53), 8.5f)
            val tw = tp.measureText(text)
            val cx = width / 2f
            val y = height - 19f
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(105, 116, 90, 53)
                strokeWidth = 1f
            }
            c.drawLine(cx - tw / 2f - 72f, y - 3f, cx - tw / 2f - 17f, y - 3f, line)
            c.drawLine(cx + tw / 2f + 17f, y - 3f, cx + tw / 2f + 72f, y - 3f, line)
            pFill.isAntiAlias = true
            pFill.color = Color.argb(145, 116, 90, 53)
            c.drawCircle(cx - tw / 2f - 10f, y - 3f, 2f, pFill)
            c.drawCircle(cx + tw / 2f + 10f, y - 3f, 2f, pFill)
            pFill.isAntiAlias = false
            c.drawText(text, cx - tw / 2f, y, tp)
            return
        }
        val h = 34f
        val r = RectF(6f, height - h - 4f, width - 6f, height - 4f)
        panel(c, r, thick = 3f)
        val p = px(th.fg, 11f)
        val parts = listOf(
            th.green to "CALL", th.fg to "=TALK  ",
            th.accent to "END", th.fg to "=STOP  ",
            th.pink to "MUTE", th.fg to "=DICTATE  ",
            th.yellow to "#", th.fg to "=CAPTURE"
        )
        var x = 20f
        for ((col, txt) in parts) { p.color = col; c.drawText(txt, x, height - 15f, p); x += p.measureText(txt) }
    }

    // ---- BOOT ---------------------------------------------------------------

    private fun drawBoot(c: Canvas) {
        val t = frame - st.bootStart
        val cx = width / 2f

        // mascot bounces in
        val drop = minOf(1f, t / 900f)
        val ease = 1 - (1 - drop) * (1 - drop) * (1 - drop)
        val bounce = if (t in 900..1150) sin((t - 900) / 250f * Math.PI).toFloat() * 10f else 0f
        val hop = (abs(sin(frame / 350.0)) * 6).toFloat()
        sprite(c, Sprites.orange, cx - 8 * 9f, -130f + (height * 0.09f + 130f) * ease + bounce - hop, 9f)

        if (t > 1000) {
            val full = "MANZANILLA OS"
            val chars = ((t - 1000) / 60).toInt().coerceAtMost(full.length)
            center(c, full.substring(0, chars), px(th.fg, 44f), height * 0.42f)
        }
        if (t > 1900) {
            val r = RectF(cx - 170, height * 0.46f, cx + 170, height * 0.46f + 42)
            panel(c, r, th.yellow)
            center(c, "STARTING UP...", px(th.yellow, 17f), height * 0.46f + 29)
        }
        if (t > 2300) {
            // loading panel with % + phases
            val r = RectF(width * 0.14f, height * 0.56f, width * 0.86f, height * 0.86f)
            panel(c, r)
            val pct = (((t - 2300) / 5200f) * 100).toInt().coerceAtMost(100)
            c.drawText("LOADING", r.left + 20, r.top + 34, px(th.green, 15f))
            center(c, "$pct%", px(th.fg, 20f), r.top + 36)
            val blocks = 22
            val done = (pct * blocks / 100)
            for (i in 0 until blocks) {
                pFill.color = if (i < done) th.green else Color.argb(120, 0, 0, 0)
                c.drawRect(r.left + 18 + i * ((r.width() - 36) / blocks),
                    r.top + 48, r.left + 14 + (i + 1) * ((r.width() - 36) / blocks), r.top + 68, pFill)
            }
            val phases = listOf("01 PAIRING BRIDGE", "02 LOADING AGENTS", "03 VOICE ROUTES")
            val pw = (r.width() - 60) / 3
            for ((i, ph) in phases.withIndex()) {
                val active = pct > i * 33
                val pr = RectF(r.left + 20 + i * (pw + 10), r.top + 84,
                    r.left + 20 + i * (pw + 10) + pw, r.bottom - 14)
                panel(c, pr, if (active) th.green else th.panelLine, 3f)
                c.drawText(ph, pr.left + 10, pr.centerY() + 5, px(if (active) th.green else th.gray, 10f))
            }
        }
        if (t > 3200 && blink) center(c, "TIP: PRESS ANY KEY TO SKIP", px(th.cyan, 12f), height * 0.93f)
    }

    // ---- HOME ---------------------------------------------------------------

    private data class Tile(val id: String, val label: String, val icon: String = "", val spr: Bitmap? = null, val glyph: String = "")
    private val tilePages = listOf(
        listOf(
            Tile("chatgpt", "CHATGPT", "chatgpt"), Tile("codex", "CODEX", "codex"),
            Tile("clor", "CLAUDE", "claude"), Tile("wispr", "WISPR", "wispr"),
            Tile("stream", "STREAM DECK", "stream"), Tile("tasks", "TASKS", "tasks"),
            Tile("files", "FILES", "files"), Tile("more", "MORE", "more", glyph = "...")
        ),
        listOf(
            Tile("comet", "COMET", "comet"), Tile("chrome", "CHROME", "chrome"),
            Tile("spotify", "SPOTIFY", "spotify"), Tile("camera", "CAMERA", "camera"),
            Tile("pet", "PETS", "pet", glyph = "O_O"), Tile("games", "GAMES", "games"),
            Tile("themes", "THEMES", "themes", glyph = "@#@"), Tile("settings", "SETTINGS", "settings")
        )
    )

    fun homeTileId(page: Int, index: Int): String? =
        tilePages.getOrNull(page)?.getOrNull(index)?.id

    private fun drawHome(c: Canvas) {
        if (auraTheme) {
            drawAuraHomeV2(c)
            return
        }
        if (orbitTheme) {
            drawOrbitHome(c)
            return
        }
        if (liquidGlass) {
            drawGlassHome(c)
            return
        }
        if (dockstationTheme) {
            drawDockstationHome(c)
            return
        }
        val cal = Calendar.getInstance()
        val hh = "%02d".format(cal.get(Calendar.HOUR_OF_DAY))
        val mm = "%02d".format(cal.get(Calendar.MINUTE))
        val clockR = RectF(18f, 62f, 216f, 166f)
        panel(c, clockR, th.cyan, 3f)
        c.drawText("$hh${if (blink) ":" else " "}$mm", 31f, 113f, px(th.fg, 32f))
        val date = "%ta %te %tb".format(cal, cal, cal).uppercase()
        c.drawText(date, 31f, 143f, px(th.yellow, 11f))
        c.drawText("MANZANILLA", 31f, 159f, px(th.gray, 8f))

        val activityR = RectF(228f, 62f, width - 18f, 166f)
        panel(c, activityR, if (st.nowKind.isNotEmpty()) th.green else th.panelLine, 3f)
        c.drawText("AI ACTIVITY", 242f, 82f, px(th.fg, 10f))
        val kinds = listOf("chatgpt" to "GPT", "codex" to "CODEX", "claude" to "CLAUDE", "cowork" to "COWORK")
        var chipX = 350f
        for ((kind, label) in kinds) {
            val running = st.aiRunning[kind] == true || st.nowKind == kind ||
                st.slots.any { it.kind == kind && it.state !in listOf("empty", "done") }
            val current = st.nowKind == kind
            val cp = px(if (current) th.yellow else if (running) th.green else th.gray, 8f)
            pFill.color = if (current) th.yellow else if (running) th.green else th.gray
            c.drawRect(chipX, 72f, chipX + 5f, 79f, pFill)
            c.drawText(label, chipX + 9f, 80f, cp)
            chipX += cp.measureText(label) + 29f
        }
        val state = if (st.nowState.isBlank()) "IDLE" else st.nowState.uppercase().replace('_', ' ')
        val who = st.nowName.ifBlank { "PC COMPANION" }
        c.drawText("NOW  $who  /  $state", 242f, 111f, px(stateColor(st.nowState), 10f))
        val taskP = px(th.fg, 11f)
        val task = st.nowTask.ifBlank {
            st.aiProjects.firstOrNull()?.task ?: "No active AI task detected"
        }
        c.drawText(fitText(task.uppercase(), taskP, activityR.width() - 28f), 242f, 134f, taskP)
        val project = st.nowProject.ifBlank { st.aiProjects.firstOrNull()?.project.orEmpty() }
        val meta = listOf(project, st.nowModel).filter { it.isNotBlank() }.joinToString("  /  ")
        if (meta.isNotBlank()) c.drawText(fitText(meta.uppercase(), px(th.cyan, 8f), activityR.width() - 28f),
            242f, 153f, px(th.cyan, 8f))

        val tiles = tilePages[st.homePage]
        val cols = 4
        val tw = (width - 40) / cols.toFloat()
        val rowY = floatArrayOf(177f, 292f)
        val tileH = 102f
        val introP = if (st.homeIntroStart > 0)
            ((frame - st.homeIntroStart) / 750f).coerceIn(0f, 1f) else 1f
        for ((i, tile) in tiles.withIndex()) {
            // staggered rise-in as the boot animation becomes the home screen
            val tp = ((introP * 1.6f) - i * 0.07f).coerceIn(0f, 1f)
            val riseDy = (1 - (1 - tp) * (1 - tp)) * 0f + (1 - tp) * (1 - tp) * 90f
            val r = RectF(20 + (i % cols) * tw + 6, rowY[i / cols] + riseDy,
                20 + (i % cols) * tw + tw - 6, rowY[i / cols] + tileH + riseDy)
            val sel = i == st.homeSel
            panel(c, r, if (sel && blink) th.yellow else if (sel) th.fg else th.panelLine,
                if (sel) 5f else 4f)
            zone(r, "tile:${tile.id}")
            val iconRect = RectF(r.centerX() - 32f, r.top + 8f, r.centerX() + 32f, r.top + 72f)
            if (liquidGlass) {
                val discColors = intArrayOf(th.green, th.accent, th.yellow, th.cyan)
                pFill.isAntiAlias = true
                pFill.color = Color.argb(24, Color.red(discColors[i % 4]),
                    Color.green(discColors[i % 4]), Color.blue(discColors[i % 4]))
                c.drawCircle(r.centerX(), r.top + 40f, 34f, pFill)
                pFill.isAntiAlias = false
            }
            if (spiderTheme) {
                SpiderIcons.draw(c, if (tile.id == "clor") "claude" else tile.id, iconRect, frame, sel)
            } else if (tile.icon.isNotEmpty() && drawGeneratedIcon(c, tile.icon, iconRect)) {
                // Generated high-density pixel art is the primary icon system.
            } else if (tile.spr != null) {
                val spr = tile.spr
                val sc = 4.4f
                sprite(c, spr, r.centerX() - spr.width * sc / 2, r.top + 12f, sc)
            } else {
                c.drawText(tile.glyph, r.centerX() - px(th.cyan, 20f).measureText(tile.glyph) / 2,
                    r.top + 46f, px(th.cyan, 20f))
            }
            // Text is always the last layer inside a card, above every icon.
            labelStrip(c, r, 27f)
            val p = px(th.fg, 10f)
            c.drawText(tile.label, r.centerX() - p.measureText(tile.label) / 2, r.bottom - 12f, p)
            // colored underline like the mockup
            pFill.color = listOf(th.green, th.accent, th.pink, th.cyan)[i % 4]
            c.drawRect(r.left + 8, r.bottom - 7f, r.right - 8, r.bottom - 4f, pFill)

            // Persistent keypad badge: the same 1..8 mapping works on both
            // home pages and in every visual theme.
            pFill.color = if (sel) th.yellow else Color.argb(235, 12, 18, 28)
            c.drawCircle(r.left + 15f, r.top + 15f, 11f, pFill)
            val badge = px(if (sel) th.bg else th.fg, 8f)
            val badgeText = (i + 1).toString()
            c.drawText(badgeText, r.left + 15f - badge.measureText(badgeText) / 2f,
                r.top + 18f, badge)
        }
        val toggleR = RectF(width - 244f, 157f, width - 18f, 178f)
        zone(toggleR, "home_page_toggle")
        val more = if (st.homePage == 0) "[9] PAGE 1/2  -/+ NEXT" else "[9] PAGE 2/2  -/+ BACK"
        val moreP = px(th.cyan, 8f)
        c.drawText(more, width - 18f - moreP.measureText(more), 172f, moreP)
    }

    /** AURA home: one information instrument plus compact numbered capsules. */
    private fun drawAuraHomeV2(c: Canvas) {
        if (st.homePage != auraShownPage) {
            auraFromPage = auraShownPage; auraShownPage = st.homePage; auraPageStartedAt = frame
        }
        if (st.homeSel != auraLastSelection) {
            auraLastSelection = st.homeSel; auraFocusStartedAt = frame
        }
        val pageT = if (auraPageStartedAt > 0L)
            ((frame-auraPageStartedAt)/620f).coerceIn(0f,1f) else 1f
        val oldTiles = tilePages[auraFromPage.coerceIn(tilePages.indices)]
        val newTiles = tilePages[st.homePage]
        val tile = if (pageT < .5f) oldTiles[st.homeSel] else newTiles[st.homeSel]
        val face = if (pageT < 1f) .045f + .955f*abs(cos(Math.PI*pageT)).toFloat() else 1f
        val focusT = if (auraFocusStartedAt > 0L)
            ((frame-auraFocusStartedAt)/420f).coerceIn(0f,1f) else 1f
        val focusSpring = if (focusT >= 1f) 1f else
            (1.0-exp(-7.5*focusT)*cos(11.0*focusT)).toFloat()

        val main = RectF(48f,76f,523f,410f)
        val sx = 545f; val gap = 11f; val cardW = (width-18f-sx-gap)/2f
        fun card(col:Int,row:Int)=RectF(sx+col*(cardW+gap),82f+row*134f,
            sx+col*(cardW+gap)+cardW,202f+row*134f)
        val cards = arrayOf(card(0,0),card(1,0),card(0,1),card(1,1))
        val strip = RectF(sx,350f,width-18f,410f)

        c.save(); c.scale(face,1f,width/2f,height/2f)
        c.save(); c.scale(1f+.008f*focusSpring,1f+.008f*focusSpring,main.centerX(),main.centerY())
        drawAuraDarkPanel(c,main)

        val white = Color.rgb(248,250,253)
        val faint = Color.argb(168,244,249,252)
        val eyebrow=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=faint;textSize=11.5f;typeface=modernFont;letterSpacing=.06f}
        val title=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=white;textSize=31f;typeface=Typeface.create("sans-serif-light",Typeface.NORMAL)}
        val body=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(205,247,250,252);textSize=14f;typeface=modernFont}
        val key = st.homeSel+1
        c.drawText("0$key  /  ${if(st.homePage==0) "COMPANION" else "TOOLS"}",main.left+27f,main.top+34f,eyebrow)
        c.drawText(tile.label,main.left+27f,main.top+78f,title)
        val detail = when(tile.id){
            "chatgpt" -> st.pcNotificationBody.ifBlank { st.nowTask.ifBlank { "Ready to reply, review, or begin a new task." } }
            "spotify" -> if(st.mediaActive) listOf(st.mediaTitle,st.mediaArtist).filter{it.isNotBlank()}.joinToString(" — ") else "Your PC media appears here when playback starts."
            "tasks" -> st.nowTask.ifBlank { "Open your current work and progress." }
            "files" -> "Continue in the files and folders connected to your PC."
            else -> "Press 5 to open ${tile.label.lowercase()}, or choose another numbered tool."
        }
        wrapWords(detail,body,(main.width()-135f).toInt()).take(2).forEachIndexed{i,line->
            c.drawText(line,main.left+27f,main.top+112f+i*20f,body)
        }

        val arrow=RectF(main.right-66f,main.top+22f,main.right-24f,main.top+64f)
        pFill.isAntiAlias=true;pFill.color=Color.argb(35,255,255,255);c.drawCircle(arrow.centerX(),arrow.centerY(),21f,pFill)
        c.drawCircle(arrow.centerX(),arrow.centerY(),21f,strokePaint(Color.argb(88,255,255,255),1f))
        val ap=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=white;textSize=21f;typeface=modernFont}
        c.drawText("↗",arrow.centerX()-ap.measureText("↗")/2f,arrow.centerY()+7f,ap)

        val graph=RectF(main.left+28f,main.top+164f,main.right-28f,main.top+247f)
        val path=Path(); for(i in 0..100 step 2){
            val x=graph.left+graph.width()*i/100f
            val peak=exp(-((i-56f)*(i-56f))/420f).toFloat()
            val ripple=sin(i*.12+frame/1550.0).toFloat()*2.2f
            val y=graph.bottom-11f-peak*55f+ripple
            if(i==0)path.moveTo(x,y)else path.lineTo(x,y)
        }
        c.drawPath(path,strokePaint(Color.argb(205,244,250,252),1.45f))
        val travel=((sin(frame/2100.0)+1.0)/2.0).toFloat()
        val gx=graph.left+graph.width()*(.43f+travel*.25f)
        val gi=((gx-graph.left)/graph.width()*100f)
        val gy=graph.bottom-11f-exp(-((gi-56f)*(gi-56f))/420f).toFloat()*55f
        pFill.color=Color.argb(41,255,255,255);c.drawCircle(gx,gy,17f,pFill)
        pFill.color=white;c.drawCircle(gx,gy,4f,pFill)

        val selectorY=main.bottom-43f
        val selectorLeft=main.left+43f
        val selectorStep=(main.width()-86f)/7f
        for(i in 0..7){
            val cx=selectorLeft+i*selectorStep; val selected=i==st.homeSel
            pFill.color=if(selected)Color.argb(236,249,251,253)else Color.argb(17,255,255,255)
            c.drawCircle(cx,selectorY,if(selected)15.5f else 14f,pFill)
            if(!selected)c.drawCircle(cx,selectorY,14f,strokePaint(Color.argb(48,255,255,255),1f))
            val np=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(selected)Color.rgb(30,38,49)else faint;textSize=10.5f;typeface=modernFont}
            val label=(i+1).toString();c.drawText(label,cx-np.measureText(label)/2f,selectorY+4f,np)
        }
        c.restore()

        val cardTints=intArrayOf(th.cyan,th.pink,th.yellow,th.green)
        cards.forEachIndexed{i,r->
            val bob=sin(frame/2600.0+i*1.31).toFloat()*1.8f
            c.save();c.translate(0f,bob);drawAuraPanel(c,r,cardTints[i])
            val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(183,29,37,49);textSize=10.5f;typeface=modernFont}
            val value=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=th.fg;textSize=19f;typeface=Typeface.create("sans-serif-light",Typeface.NORMAL)}
            when(i){
                0->{c.drawText("PC BRIDGE",r.left+16f,r.top+25f,label);c.drawText(if(st.connected)"Online" else "Offline",r.left+16f,r.top+61f,value);pFill.color=if(st.connected)th.green else th.accent;c.drawCircle(r.right-18f,r.top+18f,4f,pFill)}
                1->{c.drawText("WEEKLY",r.left+16f,r.top+25f,label);val v=if(st.weeklyLimitRemaining>=0)"${st.weeklyLimitRemaining}%" else "—";if(st.weeklyLimitRemaining>=0)drawAuraDotText(c,v,r.left+16f,r.top+44f,1.9f,th.fg)else c.drawText(v,r.left+16f,r.top+62f,value)}
                2->{c.drawText("NOTICES",r.left+16f,r.top+25f,label);val n=st.pcNotificationCount.coerceAtLeast(0).toString();drawAuraDotText(c,n,r.left+16f,r.top+43f,2.15f,th.fg);c.drawText(if(st.pcNotificationCount>0)"new" else "clear",r.left+16f,r.bottom-16f,label)}
                else->{c.drawText("OPEN",r.left+16f,r.top+25f,label);AuraIcons.draw(c,tile.id,RectF(r.centerX()-24f,r.centerY()-18f,r.centerX()+24f,r.centerY()+30f),frame)}
            }
            c.restore()
        }
        drawAuraPanel(c,strip,th.cyan)
        val stripLabel=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(170,31,40,52);textSize=10f;typeface=modernFont;letterSpacing=.04f}
        val stripValue=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=th.fg;textSize=13.5f;typeface=modernFont}
        c.drawText("CURRENT ACTIVITY",strip.left+17f,strip.top+22f,stripLabel)
        val status=when{st.pcNotificationTitle.isNotBlank()->st.pcNotificationTitle;st.nowTask.isNotBlank()->st.nowTask;st.mediaActive->st.mediaTitle;else->"Everything is ready"}
        c.drawText(fitText(status,stripValue,strip.width()-34f),strip.left+17f,strip.bottom-14f,stripValue)
        c.restore()

        zone(main,"tile:${newTiles[st.homeSel].id}"); zone(arrow,"status_expand")
        cards.forEachIndexed{i,r->zone(r,if(i==3)"tile:${newTiles[st.homeSel].id}" else "status_expand")}
        for(i in 0..7){val cx=selectorLeft+i*selectorStep;zone(RectF(cx-21f,selectorY-23f,cx+21f,selectorY+23f),"tile:${newTiles[i].id}")}
        pFill.isAntiAlias=false
    }

    private fun drawAuraHome(c: Canvas) {
        if (st.homePage != auraShownPage) {
            auraFromPage = auraShownPage
            auraShownPage = st.homePage
            auraPageStartedAt = frame
        }
        if (st.homeSel != auraLastSelection) {
            auraLastSelection = st.homeSel
            auraFocusStartedAt = frame
        }
        val top = 68f
        val bottom = 278f
        val main = RectF(18f, top, width * .59f, bottom)
        panel(c, main, th.fg, 2.1f)
        zone(main, "status_expand")

        val overline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 35, 44, 57); textSize = 12f; typeface = modernFont
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = th.fg; textSize = 25f; typeface = modernBold
        }
        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(205, 42, 52, 66); textSize = 13.5f; typeface = modernFont
        }
        val eyebrow = when {
            st.pcNotificationTitle.isNotBlank() -> "LATEST FROM CHATGPT"
            st.mediaActive -> "NOW PLAYING"
            st.nowState == "working" -> "YOUR AI COMPANION IS WORKING"
            else -> "MANZANILLA COMPANION"
        }
        val title = when {
            st.pcNotificationTitle.isNotBlank() -> st.pcNotificationTitle
            st.mediaActive -> st.mediaTitle.ifBlank { "Windows media" }
            st.nowTask.isNotBlank() -> st.nowTask
            else -> "Ready for your next task"
        }
        val detail = when {
            st.pcNotificationBody.isNotBlank() -> st.pcNotificationBody
            st.mediaActive -> listOf(st.mediaArtist, st.mediaAlbum).filter { it.isNotBlank() }.joinToString(" · ")
            st.nowDetail.isNotBlank() -> st.nowDetail
            st.connected -> "Your computer, notifications and voice controls are connected."
            else -> "Connect the PC Bridge to start working."
        }
        c.drawText(eyebrow, main.left + 22f, main.top + 29f, overline)
        wrapWords(title, titlePaint, (main.width() - 98f).toInt()).take(2).forEachIndexed { i, line ->
            c.drawText(line, main.left + 22f, main.top + 65f + i * 29f, titlePaint)
        }
        c.drawText(fitText(detail, detailPaint, main.width() - 46f),
            main.left + 22f, main.top + 128f, detailPaint)

        // The living line responds to state instead of pretending to be a
        // precise graph. Its dot moves while the agent is active.
        val graph = RectF(main.left + 23f, main.top + 144f, main.right - 23f, main.bottom - 22f)
        val path = Path()
        val activity = if (st.nowState == "working" || st.mediaActive) 1f else .42f
        for (x in 0..100 step 2) {
            val xf = graph.left + graph.width() * x / 100f
            val bell = exp(-((x - 57f) * (x - 57f)) / 410f).toFloat()
            val y = graph.bottom - 12f - bell * graph.height() * .72f * activity
            if (x == 0) path.moveTo(xf, y) else path.lineTo(xf, y)
        }
        c.drawPath(path, strokePaint(Color.argb(190, 34, 49, 65), 1.8f))
        val dotX = graph.left + graph.width() * (.50f + sin(frame / 1700.0).toFloat() * .08f)
        val normalized = ((dotX - graph.left) / graph.width()) * 100f
        val bell = exp(-((normalized - 57f) * (normalized - 57f)) / 410f).toFloat()
        val dotY = graph.bottom - 12f - bell * graph.height() * .72f * activity
        pFill.color = Color.argb(66, 255, 255, 255); c.drawCircle(dotX, dotY, 16f, pFill)
        pFill.color = th.fg; c.drawCircle(dotX, dotY, 4.5f, pFill)

        val arrow = RectF(main.right - 55f, main.top + 17f, main.right - 17f, main.top + 55f)
        pFill.color = Color.argb(74, 255, 255, 255)
        c.drawRoundRect(arrow, 19f, 19f, pFill)
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.fg; textSize = 21f; typeface = modernFont }
        c.drawText("↗", arrow.centerX() - arrowPaint.measureText("↗") / 2f, arrow.centerY() + 7f, arrowPaint)

        val rightLeft = main.right + 12f
        val rightWidth = width - 18f - rightLeft
        val gap = 10f
        val half = (rightWidth - gap) / 2f
        val bridge = RectF(rightLeft, top, rightLeft + half, 163f)
        val usage = RectF(bridge.right + gap, top, width - 18f, 163f)
        val notice = RectF(rightLeft, 174f, width - 18f, bottom)
        panel(c, bridge, if (st.connected) th.green else th.accent, 1.7f)
        panel(c, usage, th.cyan, 1.7f)
        panel(c, notice, th.pink, 1.7f)

        val smallLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.gray; textSize = 11.5f; typeface = modernFont }
        val smallValue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.fg; textSize = 19f; typeface = modernBold }
        c.drawText("PC BRIDGE", bridge.left + 15f, bridge.top + 24f, smallLabel)
        c.drawText(if (st.connected) "ONLINE" else "OFFLINE", bridge.left + 15f, bridge.top + 58f, smallValue)
        pFill.color = if (st.connected) th.green else th.accent
        c.drawCircle(bridge.right - 19f, bridge.top + 20f, 5f, pFill)

        c.drawText("WEEKLY", usage.left + 15f, usage.top + 24f, smallLabel)
        val week = if (st.weeklyLimitRemaining >= 0) "${st.weeklyLimitRemaining}%" else "—"
        if (st.weeklyLimitRemaining >= 0) {
            drawAuraDotText(c, week, usage.left + 15f, usage.top + 38f, 2.15f, th.fg)
        } else {
            c.drawText(week, usage.left + 15f, usage.top + 58f, smallValue)
        }

        c.drawText("ACTIVITY", notice.left + 15f, notice.top + 24f, smallLabel)
        val noticeValue = when {
            st.pcNotificationCount > 0 -> "${st.pcNotificationCount} NEW"
            st.nowState == "working" -> "WORKING"
            else -> "ALL CLEAR"
        }
        if (st.pcNotificationCount > 0) {
            drawAuraDotText(c, st.pcNotificationCount.toString(), notice.left + 15f,
                notice.top + 37f, 2.15f, th.fg)
            val newPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = th.fg; textSize = 12f; typeface = modernBold
            }
            c.drawText("NEW", notice.left + 47f, notice.top + 57f, newPaint)
        } else {
            c.drawText(noticeValue, notice.left + 15f, notice.top + 57f, smallValue)
        }
        val state = st.nowState.ifBlank { "ready" }.uppercase().replace('_', ' ')
        c.drawText(fitText(state, smallLabel, notice.width() - 30f), notice.left + 15f, notice.bottom - 17f, smallLabel)

        // Eight physical-key destinations remain visible at laptop distance.
        val tiles = tilePages[st.homePage]
        val pageProgress = if (auraPageStartedAt > 0L)
            ((frame - auraPageStartedAt) / 680f).coerceIn(0f, 1f) else 1f
        val dockTop = 297f
        val dockBottom = height - 32f
        val outer = 18f
        val tileGap = 7f
        val tileWidth = (width - outer * 2f - tileGap * 7f) / 8f
        tiles.forEachIndexed { i, destinationTile ->
            val localPage = ((pageProgress * 1.32f) - i * .045f).coerceIn(0f, 1f)
            val pageTurning = localPage < 1f
            val tile = if (pageTurning && localPage < .5f)
                tilePages[auraFromPage.coerceIn(tilePages.indices)][i] else destinationTile
            val faceScale = if (pageTurning)
                (.08f + .92f * abs(cos(Math.PI * localPage)).toFloat()) else 1f
            val focusRaw = if (i == st.homeSel && auraFocusStartedAt > 0L)
                ((frame - auraFocusStartedAt) / 360f).coerceIn(0f, 1f) else 1f
            val spring = if (focusRaw >= 1f) 1f else
                (1.0 - exp(-8.0 * focusRaw) * cos(12.0 * focusRaw)).toFloat()
            val lift = if (i == st.homeSel) 5f * spring else 0f
            val left = outer + i * (tileWidth + tileGap)
            val r = RectF(left, dockTop - lift, left + tileWidth, dockBottom - lift)
            c.save()
            c.scale(faceScale, 1f, r.centerX(), r.centerY())
            panel(c, r, if (i == st.homeSel) th.fg else th.panelLine, if (i == st.homeSel) 2.2f else 1.2f)

            val iconSize = if (i == st.homeSel) 52f else 45f
            val iconR = RectF(r.centerX() - iconSize / 2f, r.top + 22f,
                r.centerX() + iconSize / 2f, r.top + 22f + iconSize)
            val iconId = if (tile.id == "clor") "claude" else tile.id
            AuraIcons.draw(c, iconId, iconR, frame)

            val badgeR = RectF(r.left + 7f, r.top + 7f, r.left + 31f, r.top + 31f)
            pFill.color = if (i == st.homeSel) th.fg else Color.argb(150, 255, 255, 255)
            c.drawRoundRect(badgeR, 12f, 12f, pFill)
            val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (i == st.homeSel) Color.WHITE else th.fg
                textSize = 13f; typeface = modernBold
            }
            val n = (i + 1).toString()
            c.drawText(n, badgeR.centerX() - number.measureText(n) / 2f, badgeR.centerY() + 4.5f, number)

            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = th.fg; textSize = if (tile.label.length > 9) 9.6f else 11f; typeface = modernBold
            }
            c.drawText(tile.label, r.centerX() - label.measureText(tile.label) / 2f, r.bottom - 13f, label)
            c.restore()
            zone(RectF(r.left - 2f, r.top - 3f, r.right + 2f, r.bottom + 4f),
                "tile:${destinationTile.id}")
        }
    }

    /** Small 3x5 dot display used for live values, never baked into an image. */
    private fun drawAuraDotText(c: Canvas, value: String, x: Float, y: Float,
        radius: Float, color: Int) {
        val glyphs = mapOf(
            '0' to intArrayOf(7, 5, 5, 5, 7), '1' to intArrayOf(2, 6, 2, 2, 7),
            '2' to intArrayOf(7, 1, 7, 4, 7), '3' to intArrayOf(7, 1, 7, 1, 7),
            '4' to intArrayOf(5, 5, 7, 1, 1), '5' to intArrayOf(7, 4, 7, 1, 7),
            '6' to intArrayOf(7, 4, 7, 5, 7), '7' to intArrayOf(7, 1, 1, 1, 1),
            '8' to intArrayOf(7, 5, 7, 5, 7), '9' to intArrayOf(7, 5, 7, 1, 7),
            ':' to intArrayOf(0, 2, 0, 2, 0), '%' to intArrayOf(5, 1, 2, 4, 5)
        )
        val step = radius * 2.65f
        var cursor = x
        pFill.isAntiAlias = true
        pFill.color = color
        value.forEach { ch ->
            val rows = glyphs[ch]
            if (rows != null) {
                rows.forEachIndexed { row, bits ->
                    for (col in 0..2) if ((bits and (1 shl (2 - col))) != 0) {
                        c.drawCircle(cursor + col * step, y + row * step, radius, pFill)
                    }
                }
            }
            cursor += step * if (ch == ':') 2f else 4f
        }
        pFill.isAntiAlias = false
    }

    private fun auraDotTextWidth(value: String, radius: Float): Float {
        val step = radius * 2.65f
        return value.sumOf { if (it == ':') 2.0 else 4.0 }.toFloat() * step - step
    }

    private fun drawDockLimitDial(c: Canvas, cx: Float, cy: Float, label: String,
        value: Int, color: Int) {
        val radius = 28f
        val ring = RectF(cx-radius,cy-radius,cx+radius,cy+radius)
        c.drawArc(ring,-90f,360f,false,strokePaint(Color.argb(43,225,240,252),4.6f))
        if(value>=0)c.drawArc(ring,-90f,360f*value.coerceIn(0,100)/100f,false,strokePaint(color,4.6f))
        val lp=dockText(Color.argb(218,229,241,252),9.5f,false)
        c.drawText(label,cx-lp.measureText(label)/2f,cy-radius-8f,lp)
        val shown=if(value>=0)"$value%" else "—"
        if(value>=0){
            val dotRadius=1.12f
            drawAuraDotText(c,shown,cx-auraDotTextWidth(shown,dotRadius)/2f,cy-5f,dotRadius,Color.WHITE)
        }else{
            val ep=dockText(Color.WHITE,15f,false);c.drawText(shown,cx-ep.measureText(shown)/2f,cy+5f,ep)
        }
    }

    /**
     * Dockstation keeps the dependable two-page launcher model but presents it
     * as a low-resource XMB descendant: icon objects float directly over the
     * animated ORBIT field, with one useful PC/AI/media instrument panel.
     */
    private fun drawDockstationHome(c: Canvas) {
        // Page changes behave like eight physical, two-sided objects. Each
        // face narrows to its vertical axis, swaps identity edge-on, and opens
        // as the icon occupying the same numbered key on the other page.
        if (st.homePage != orbitShownPage) {
            orbitFromPage = orbitShownPage
            orbitShownPage = st.homePage
            orbitPageStartedAt = frame
        }
        dockInfoPane.draw(c, width.toFloat(), st, frame, ::zone, ::drawAuraDotText)

        val tiles = tilePages[st.homePage]
        val fromTiles = tilePages[orbitFromPage.coerceIn(tilePages.indices)]
        val pageAnimating = orbitPageStartedAt > 0L && frame - orbitPageStartedAt < 650L
        val centersX = floatArrayOf(112f, 322f, 532f, 742f)
        val centersY = floatArrayOf(310f, 401f)
        tiles.forEachIndexed { i, tile ->
            // Dockstation is a direct-number launcher, not a focus cursor. A
            // persistent homeSel made app 1 pulse yellow forever at startup.
            val selected = false
            val cx = centersX[i % 4]
            val cy = centersY[i / 4]
            if (selected) {
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(if (blink) 82 else 54, 102, 221, 255)
                    maskFilter = BlurMaskFilter(19f, BlurMaskFilter.Blur.NORMAL)
                }
                c.drawCircle(cx, cy, 50f, glow)
            }
            val local = if (pageAnimating)
                ((frame - orbitPageStartedAt - i * 28L) / 390f).coerceIn(0f, 1f) else 1f
            val turn = (1f - cos(local * Math.PI).toFloat()) / 2f
            val face = if (turn < .5f) fromTiles[i] else tile
            val faceScale = kotlin.math.abs(cos(turn * Math.PI).toFloat()).coerceAtLeast(.025f)
            val lift = sin(turn * Math.PI).toFloat() * 5f
            val size = if (selected) 66f else 54f
            val icon = RectF(cx - size / 2f, cy - size / 2f - lift,
                cx + size / 2f, cy + size / 2f - lift)
            c.save()
            c.scale(faceScale, 1f + (1f - faceScale) * .08f, cx, cy)
            if (!drawGeneratedIcon(c, if (face.id == "clor") "claude" else face.id, icon)) {
                val gp = dockText(Color.WHITE, 19f)
                c.drawText(face.glyph, cx - gp.measureText(face.glyph) / 2f, cy + 6f, gp)
            }
            c.restore()
            val lp = dockText(Color.argb(235, 235, 245, 255), 13f, false)
            c.drawText(face.label, cx - lp.measureText(face.label) / 2f, cy + 39f, lp)
            val badgeX = cx - 45f
            val badgeY = cy - 35f
            pFill.color = Color.argb(210, 4, 18, 42)
            c.drawCircle(badgeX, badgeY, 12f, pFill)
            val badgeText=(i+1).toString();val dotRadius=.72f
            drawAuraDotText(c,badgeText,badgeX-auraDotTextWidth(badgeText,dotRadius)/2f,
                badgeY-4f,dotRadius,Color.WHITE)
            zone(RectF(cx - 77f, cy - 37f, cx + 77f, cy + 44f), "tile:${tile.id}")
        }
        val page = dockText(Color.argb(190, 230, 242, 255), 9f)
        val pageLabel = "9 / − / +     PAGE ${st.homePage + 1} OF 2"
        c.drawText(pageLabel, width - 18f - page.measureText(pageLabel), 269f, page)
        zone(RectF(width - 270f, 251f, width - 8f, 275f), "home_page_toggle")
    }

    private fun drawDockStatusCard(c: Canvas, cardIndex: Int) {
        val icon = RectF(238f, 47f, 330f, 139f)
        val textX = 348f
        when (cardIndex) {
            0 -> {
                if (st.mediaActive) {
                    val mediaIcon = if (st.mediaSource.contains("spotify", true)) "spotify" else "play"
                    drawGeneratedIcon(c, mediaIcon, icon)
                    c.drawText("NOW PLAYING", textX, 47f, dockText(Color.rgb(104, 234, 191), 11f))
                    val title = dockText(Color.WHITE, 24f, false)
                    c.drawText(fitText(st.mediaTitle.ifBlank { "Windows media" }, title, 300f),
                        textX, 84f, title)
                    val artist = listOf(st.mediaArtist, st.mediaAlbum)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    val artistPaint = dockText(Color.argb(210, 214, 232, 255), 13f, false)
                    c.drawText(fitText(artist, artistPaint, 310f), textX, 116f, artistPaint)
                    val controls = listOf("media_previous" to "‹‹", "media_toggle" to
                        if (st.mediaStatus.contains("playing", true)) "Ⅱ" else "▶", "media_next" to "››")
                    controls.forEachIndexed { i, (id, glyph) ->
                        val cx = 694f + i * 55f
                        pFill.color = Color.argb(74, 255, 255, 255)
                        c.drawCircle(cx, 94f, 19f, pFill)
                        val gp = dockText(Color.WHITE, 13f, false)
                        c.drawText(glyph, cx - gp.measureText(glyph) / 2f, 99f, gp)
                        zone(RectF(cx - 24f, 69f, cx + 24f, 119f), id)
                    }
                    c.drawText("TAP FOR FULL-SCREEN CASSETTE", textX, 151f,
                        dockText(Color.argb(195, 255, 255, 255), 10f))
                } else {
                    drawGeneratedIcon(c, "play", icon)
                    c.drawText("MEDIA", textX, 47f, dockText(Color.rgb(104, 234, 191), 11f))
                    c.drawText("Nothing playing right now", textX, 88f,
                        dockText(Color.WHITE, 24f, false))
                    c.drawText("Start Spotify, YouTube, or another Windows player.", textX, 121f,
                        dockText(Color.argb(205, 214, 232, 255), 13f, false))
                }
            }
            1 -> {
                val app = st.pcNotificationApp.ifBlank { st.events.firstOrNull()?.second ?: "WINDOWS" }
                drawGeneratedIcon(c, app, icon)
                c.drawText("ACTIVITY  ·  ${st.pcNotificationCount} ALERTS", textX, 47f,
                    dockText(Color.rgb(255, 215, 107), 11f))
                val latest = st.pcNotificationTitle.ifBlank {
                    st.events.firstOrNull()?.let { "${it.second} ${it.third}" } ?: "You are all caught up"
                }
                val title = dockText(Color.WHITE, 23f, false)
                c.drawText(fitText(latest, title, width - textX - 24f), textX, 86f, title)
                val recent = st.events.take(2).joinToString("   ·   ") { "${it.first} ${it.second}" }
                val detail = recent.ifBlank { "Important PC and AI updates appear here" }
                c.drawText(fitText(detail, dockText(Color.argb(205, 214, 232, 255), 12f, false),
                    width - textX - 24f), textX, 119f,
                    dockText(Color.argb(205, 214, 232, 255), 12f, false))
                c.drawText("TAP TO OPEN THE ACTIVITY TIMELINE", textX, 151f,
                    dockText(Color.argb(195, 255, 255, 255), 10f))
            }
            else -> {
                drawGeneratedIcon(c, if (st.connected) "files" else "more", icon)
                val color = if (st.connected) Color.rgb(98, 235, 185) else Color.rgb(255, 151, 91)
                c.drawText("MANZANILLA LINK", textX, 47f, dockText(color, 11f))
                c.drawText(if (st.connected) "Connected to ${st.bridgeName.ifBlank { "your computer" }}"
                    else "Computer bridge is offline", textX, 86f,
                    dockText(Color.WHITE, 23f, false))
                c.drawText("VOLUME ${st.deviceVolume}%   ·   BRIGHTNESS ${st.deviceBrightness}%",
                    textX, 119f, dockText(Color.argb(210, 214, 232, 255), 13f, false))
                c.drawText(if (st.connected) "INPUT · NOTIFICATIONS · APPS · MEDIA READY"
                    else "Tap for connection help and device controls", textX, 151f,
                    dockText(Color.argb(195, 255, 255, 255), 10f))
            }
        }
    }

    private fun drawStatusPager(c: Canvas, selected: Int, panel: RectF) {
        val labels = arrayOf("MEDIA", "ACTIVITY", "LINK")
        val totalWidth = 183f
        val startX = panel.right - totalWidth - 18f
        labels.forEachIndexed { i, label ->
            val x = startX + i * 61f
            pFill.color = if (i == selected) Color.argb(220, 102, 221, 255)
                else Color.argb(60, 255, 255, 255)
            c.drawRoundRect(RectF(x, panel.bottom - 24f, x + 52f, panel.bottom - 10f), 7f, 7f, pFill)
            val lp = dockText(if (i == selected) Color.rgb(3, 20, 39)
                else Color.argb(220, 240, 247, 255), 9f, false)
            c.drawText(label, x + 26f - lp.measureText(label) / 2f, panel.bottom - 14f, lp)
        }
    }

    /**
     * Full-screen form of Manzanilla Dynamic Status. The home instrument panel
     * is painted first and the same rounded surface grows over it, so a tap
     * reads as one object changing purpose rather than a separate app opening.
     */
    private fun drawAuraDynamicStatus(c: Canvas) {
        drawAuraHomeV2(c)
        hitZones.clear()
        pFill.color = Color.argb(112, 246, 249, 251)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)

        val raw = ((frame - transStart) / 430f).coerceIn(0f, 1f)
        val morph = (1.0 - exp(-7.2 * raw) * cos(11.0 * raw)).toFloat().coerceIn(0f, 1f)
        val start = dockStatusBounds()
        val end = RectF(17f, 63f, width - 17f, height - 18f)
        val surface = lerpRect(start, end, morph)
        panel(c, surface, th.fg, 2f)

        val contentAlpha = (((raw - .14f) / .54f).coerceIn(0f, 1f) * 255).toInt()
        if (contentAlpha > 0) {
            val layer = c.saveLayerAlpha(surface, contentAlpha)
            val selected = st.statusCardIndex.coerceIn(0, 2)
            val overline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = th.gray; textSize = 13f; typeface = modernBold; letterSpacing = .06f
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = th.fg; textSize = 31f; typeface = modernBold
            }
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 34, 43, 56); textSize = 17f; typeface = modernFont
            }
            val eyebrow: String
            val title: String
            val body: String
            when (selected) {
                0 -> {
                    eyebrow = "MEDIA ON YOUR COMPUTER"
                    title = if (st.mediaActive) st.mediaTitle.ifBlank { "Now playing" } else "Nothing is playing"
                    body = if (st.mediaActive) listOf(st.mediaArtist, st.mediaAlbum)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    else "Start music or a video on the PC and controls will appear here."
                }
                1 -> {
                    eyebrow = "LATEST FROM CHATGPT"
                    title = st.pcNotificationTitle.ifBlank { "No new message" }
                    body = st.pcNotificationBody.ifBlank {
                        st.events.firstOrNull()?.third ?: "The next important response will appear here."
                    }
                }
                else -> {
                    eyebrow = "MANZANILLA LINK"
                    title = if (st.connected) "Connected to ${st.bridgeName.ifBlank { "your computer" }}"
                    else "Computer bridge is offline"
                    body = if (st.connected) "Voice, notifications, media and app controls are ready."
                    else "Start the PC Bridge and keep both devices on the same network."
                }
            }
            c.drawText(eyebrow, surface.left + 27f, surface.top + 38f, overline)
            c.drawText(fitText(title, titlePaint, surface.width() - 108f),
                surface.left + 27f, surface.top + 83f, titlePaint)
            wrapWords(body, bodyPaint, (surface.width() - 56f).toInt()).take(5).forEachIndexed { i, line ->
                c.drawText(line, surface.left + 27f, surface.top + 125f + i * 24f, bodyPaint)
            }

            if (selected == 1 && st.pcNotificationApp.lowercase() in setOf("chatgpt", "codex")) {
                val reply = RectF(surface.left + 27f, surface.bottom - 67f,
                    surface.left + 257f, surface.bottom - 22f)
                val open = RectF(reply.right + 13f, reply.top, reply.right + 213f, reply.bottom)
                panel(c, reply, th.green, 1.8f)
                panel(c, open, th.cyan, 1.8f)
                val action = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.fg; textSize = 14f; typeface = modernBold }
                val replyText = "1  REPLY WITH VOICE"
                val openText = "2  OPEN ON PC"
                c.drawText(replyText, reply.centerX() - action.measureText(replyText) / 2f,
                    reply.centerY() + 5f, action)
                c.drawText(openText, open.centerX() - action.measureText(openText) / 2f,
                    open.centerY() + 5f, action)
                zone(reply, "notification_reply")
                zone(open, "chat_preview_open_pc")
            }
            val close = RectF(surface.right - 111f, surface.top + 18f, surface.right - 18f, surface.top + 51f)
            pFill.color = Color.argb(135, 255, 255, 255)
            c.drawRoundRect(close, 16f, 16f, pFill)
            val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = th.fg; textSize = 12f; typeface = modernBold }
            val closeText = "0  BACK"
            c.drawText(closeText, close.centerX() - closePaint.measureText(closeText) / 2f,
                close.centerY() + 4f, closePaint)
            zone(close, "status_close")
            c.restoreToCount(layer)
        }
        zone(RectF(0f, 0f, width.toFloat(), 61f), "status_close")
    }

    private fun drawDynamicStatus(c: Canvas) {
        if (auraTheme) {
            drawAuraDynamicStatus(c)
            return
        }
        drawDockstationHome(c)
        hitZones.clear()

        val raw = ((frame - transStart) / 520f).coerceIn(0f, 1f)
        val morph = (1.0 - exp(-6.8 * raw) * cos(10.5 * raw)).toFloat().coerceIn(0f, 1f)
        pFill.color = Color.argb((165 * morph).toInt(), 0, 5, 15)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)

        val selectedCard = st.statusCardIndex.coerceIn(0, 2)
        val mediaMode = selectedCard == 0 && st.mediaActive
        val start = dockStatusBounds()
        val end = if (mediaMode) RectF(-2f, -2f, width + 2f, height + 2f)
            else RectF(9f, 8f, width - 9f, height - 8f)
        val panel = lerpRect(start, end, morph)
        val radius = if (mediaMode) 22f * (1f - morph) else 22f + 12f * morph
        pFill.isAntiAlias = true
        pFill.color = Color.argb(246, 3, 14, 32)
        c.drawRoundRect(panel, radius, radius, pFill)
        if (!mediaMode || morph < .92f) {
            val edge = strokePaint(Color.argb((185 * (1f - if (mediaMode) morph else 0f)).toInt(),
                91, 211, 255), 1.6f)
            c.drawRoundRect(panel, radius, radius, edge)
        }

        val clip = Path().apply { addRoundRect(panel, radius, radius, Path.Direction.CW) }
        val save = c.save()
        c.clipPath(clip)
        val contentAlpha = (((raw - .20f) / .52f).coerceIn(0f, 1f) * 255).toInt()
        if (contentAlpha > 0) {
            val layer = c.saveLayerAlpha(panel, contentAlpha)
            when (selectedCard) {
                0 -> if (st.mediaActive) drawExpandedMedia(c, morph) else drawExpandedMediaIdle(c)
                1 -> drawExpandedActivity(c)
                else -> drawExpandedDevice(c)
            }
            c.restoreToCount(layer)
        }
        c.restoreToCount(save)

        if (!mediaMode) {
            val close = RectF(width - 73f, 20f, width - 21f, 72f)
            pFill.color = Color.argb(120, 8, 15, 26)
            c.drawCircle(close.centerX(), close.centerY(), 22f, pFill)
            val xp = dockText(Color.WHITE, 21f, false)
            c.drawText("×", close.centerX() - xp.measureText("×") / 2f,
                close.centerY() + 7f, xp)
            zone(close, "status_close")
        }
        pFill.isAntiAlias = false
    }

    private fun lerpRect(a: RectF, b: RectF, t: Float) = RectF(
        a.left + (b.left - a.left) * t,
        a.top + (b.top - a.top) * t,
        a.right + (b.right - a.right) * t,
        a.bottom + (b.bottom - a.bottom) * t
    )

    private fun drawExpandedMedia(c: Canvas, morph: Float) {
        // The generated source contains transparent isolation padding. Render
        // beyond the viewport so the physical shell—not that padding—meets all
        // four screen edges on the 854x480 panel.
        val cassetteEnd = RectF(-55f, -30f, width + 55f, height + 30f)
        val cassetteStart = RectF(267f, 42f, 353f, 127f)
        val cassetteP = ((morph - .12f) / .88f).coerceIn(0f, 1f)
        val cassette = lerpRect(cassetteStart, cassetteEnd, cassetteP)
        spritePaint.isFilterBitmap = true
        spritePaint.alpha = 255
        c.drawBitmap(mediaCassetteShell, null, cassette, spritePaint)
        zone(RectF(0f, 0f, width.toFloat(), height.toFloat()), "status_close")

        // A deterministic title-derived sticker is the offline fallback. When
        // the Bridge supplies cover art, this exact rectangle remains its slot.
        val hue = (kotlin.math.abs(st.mediaTitle.hashCode()) % 360).toFloat()
        val artSize = cassette.width() * .125f
        val art = RectF(cassette.centerX() - artSize / 2f,
            cassette.top + cassette.height() * .335f,
            cassette.centerX() + artSize / 2f,
            cassette.top + cassette.height() * .335f + artSize)
        val cover = st.mediaArtwork
        c.save()
        c.rotate(-2.3f, art.centerX(), art.centerY())
        pFill.color = Color.argb(238, 246, 238, 216)
        c.drawRoundRect(RectF(art.left - 6f, art.top - 6f, art.right + 6f, art.bottom + 6f),
            8f, 8f, pFill)
        if (cover != null && !cover.isRecycled) {
            val coverClip = Path().apply { addRoundRect(art, 7f, 7f, Path.Direction.CW) }
            c.save()
            c.clipPath(coverClip)
            c.drawBitmap(cover, null, art, spritePaint)
            c.restore()
        } else {
            val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(art.left, art.top, art.right, art.bottom,
                    Color.HSVToColor(floatArrayOf(hue, .72f, .88f)),
                    Color.HSVToColor(floatArrayOf((hue + 82f) % 360f, .82f, .48f)),
                    Shader.TileMode.CLAMP)
            }
            c.drawRoundRect(art, 7f, 7f, artPaint)
            val initial = st.mediaTitle.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M"
            val initialPaint = dockText(Color.WHITE, 35f, false)
            c.drawText(initial, art.centerX() - initialPaint.measureText(initial) / 2f,
                art.centerY() + 12f, initialPaint)
        }
        c.restore()

        val title = st.mediaTitle.ifBlank { "MIX TAPE" }
        val titlePaint = dockText(Color.rgb(22, 28, 38), 15f, false)
        c.drawText(fitText(title, titlePaint, cassette.width() * .30f),
            cassette.centerX() - titlePaint.measureText(fitText(title, titlePaint, cassette.width() * .30f)) / 2f,
            cassette.top + cassette.height() * .285f, titlePaint)

        // Some Spotify Windows builds expose a numeric/paused transport state
        // while audio continues. A visible cassette is an active media state,
        // so its mechanism remains alive regardless of that unreliable flag.
        val spin = (frame % 1350L) / 1350f * 360f
        drawCassetteReel(c, cassette.left + cassette.width() * .303f,
            cassette.top + cassette.height() * .445f, cassette.width() * .058f, spin)
        drawCassetteReel(c, cassette.left + cassette.width() * .703f,
            cassette.top + cassette.height() * .445f, cassette.width() * .058f, -spin * 1.04f)
    }

    private fun drawCassetteReel(c: Canvas, cx: Float, cy: Float, radius: Float, angle: Float) {
        pFill.color = Color.argb(190, 5, 13, 27)
        c.drawCircle(cx, cy, radius, pFill)
        val ring = strokePaint(Color.argb(235, 239, 229, 203), maxOf(2f, radius * .13f))
        c.drawCircle(cx, cy, radius * .76f, ring)
        c.save()
        c.rotate(angle, cx, cy)
        repeat(6) { i ->
            val a = Math.toRadians((i * 60).toDouble())
            c.drawLine(cx + cos(a).toFloat() * radius * .22f, cy + sin(a).toFloat() * radius * .22f,
                cx + cos(a).toFloat() * radius * .66f, cy + sin(a).toFloat() * radius * .66f, ring)
        }
        pFill.color = Color.rgb(255, 184, 62)
        c.drawCircle(cx + radius * .53f, cy, maxOf(3.3f, radius * .105f), pFill)
        c.restore()
        pFill.color = Color.rgb(231, 220, 194)
        c.drawCircle(cx, cy, radius * .16f, pFill)
    }

    private fun drawExpandedNotification(c: Canvas) {
        val app = st.pcNotificationApp.ifBlank { "WINDOWS" }
        drawGeneratedIcon(c, app, RectF(43f, 48f, 151f, 156f))
        c.drawText("NOTIFICATION FROM ${app.uppercase()}", 177f, 70f,
            dockText(Color.rgb(255, 212, 103), 13f))
        val title = dockText(Color.WHITE, 27f, false)
        c.drawText(fitText(st.pcNotificationTitle, title, width - 212f), 177f, 118f, title)
        val card = RectF(42f, 185f, width - 42f, height - 48f)
        pFill.color = Color.argb(72, 255, 255, 255)
        c.drawRoundRect(card, 24f, 24f, pFill)
        c.drawText("Manzanilla kept this update on the companion display", card.left + 26f,
            card.top + 48f, dockText(Color.WHITE, 17f, false))
        c.drawText("Tap × or press 0 when you are ready to return.", card.left + 26f,
            card.top + 86f, dockText(Color.argb(205, 214, 232, 248), 13f, false))
    }

    private fun drawExpandedMediaIdle(c: Canvas) {
        drawGeneratedIcon(c, "play", RectF(52f, 50f, 166f, 164f))
        c.drawText("MEDIA COMPANION", 190f, 72f, dockText(Color.rgb(104, 234, 191), 13f))
        c.drawText("Nothing is playing", 190f, 121f, dockText(Color.WHITE, 29f, false))
        c.drawText("Start Spotify, YouTube, or another Windows media app.", 190f, 158f,
            dockText(Color.argb(210, 215, 233, 250), 15f, false))
        val card = RectF(52f, 210f, width - 52f, 350f)
        pFill.color = Color.argb(58, 255, 255, 255)
        c.drawRoundRect(card, 25f, 25f, pFill)
        c.drawText("Album art and controls will appear here automatically.", card.left + 28f,
            card.top + 58f, dockText(Color.WHITE, 17f, false))
        c.drawText("Swipe left or right to visit another status card.", card.left + 28f,
            card.top + 99f, dockText(Color.argb(205, 215, 233, 250), 14f, false))
    }

    var notificationPage = 0
    private var readerMessage = ""

    private fun drawExpandedActivity(c: Canvas) {
        val message = st.pcNotificationBody.ifBlank {
            st.nowTask.ifBlank { st.pcNotificationTitle.ifBlank { "Your next update will appear here." } }
        }
        if (readerMessage != message) { readerMessage = message; notificationPage = 0 }
        val title = st.pcNotificationApp.ifBlank { "Companion" }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(139,235,215); textSize = 14f; typeface = modernFont
        }
        c.drawText("$title · Latest update", 35f, 44f, label)
        val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 25f; typeface = modernFont
        }
        val readerTitle = if(st.pcNotificationTitle.contains("PRESS TO RESPOND",true))
            "Your latest response" else st.pcNotificationTitle.ifBlank { "Ready when you are" }
        c.drawText(fitText(readerTitle,hp,width-130f),35f,83f,hp)
        val body = RectF(28f,105f,width-28f,height-87f)
        pFill.color = Color.argb(17,226,240,255)
        c.drawRoundRect(body,24f,24f,pFill)
        val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(235,244,252); textSize = 21f; typeface = Typeface.create("sans-serif",Typeface.NORMAL)
        }
        val lines = wrapWords(message,bp,(body.width()-42f).toInt())
        val perPage = maxOf(1,((body.height()-36f)/29f).toInt())
        val pages = maxOf(1,(lines.size+perPage-1)/perPage)
        notificationPage = notificationPage.coerceIn(0,pages-1)
        lines.drop(notificationPage*perPage).take(perPage).forEachIndexed { i,line ->
            c.drawText(line,body.left+21f,body.top+32f+i*29f,bp)
        }
        fun button(r:RectF,text:String,id:String,primary:Boolean=false) {
            pFill.color = if(primary)Color.rgb(128,237,206) else Color.argb(29,225,240,255)
            c.drawRoundRect(r,21f,21f,pFill)
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                color=if(primary)Color.rgb(20,52,46) else Color.WHITE;textSize=15f;typeface=modernFont
            }
            c.drawText(text,r.centerX()-p.measureText(text)/2f,r.centerY()+5f,p);zone(r,id)
        }
        val bottom=height-24f
        if(st.pcNotificationApp.lowercase() in setOf("chatgpt", "codex")) {
            button(RectF(28f,bottom-43f,242f,bottom),"1  Reply with voice","notification_reply",true)
            button(RectF(253f,bottom-43f,428f,bottom),"2  Open on PC","chat_preview_open_pc")
        }
        if(pages>1){
            if(notificationPage>0)button(RectF(width-276f,bottom-43f,width-206f,bottom),"7  ‹","reader_prev")
            if(notificationPage<pages-1)button(RectF(width-98f,bottom-43f,width-28f,bottom),"9  ›","reader_next")
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=14f;typeface=modernFont}
            c.drawText("${notificationPage+1} / $pages",width-179f,bottom-16f,p)
        }
    }

    private fun drawExpandedDevice(c: Canvas) {
        drawGeneratedIcon(c, if (st.connected) "files" else "more", RectF(43f, 42f, 157f, 156f))
        val color = if (st.connected) Color.rgb(98, 235, 185) else Color.rgb(255, 151, 91)
        c.drawText("MANZANILLA LINK", 181f, 63f, dockText(color, 13f))
        val title = if (st.connected) "Connected to ${st.bridgeName.ifBlank { "your computer" }}"
            else "Computer bridge is offline"
        c.drawText(fitText(title, dockText(Color.WHITE, 28f, false), width - 217f),
            181f, 111f, dockText(Color.WHITE, 28f, false))
        c.drawText("Your private input, notification, app, and media channel", 181f, 146f,
            dockText(Color.argb(210, 214, 232, 248), 14f, false))
        val cards = listOf(
            Triple("VOLUME", "${st.deviceVolume}%", Color.rgb(104, 234, 191)),
            Triple("BRIGHTNESS", "${st.deviceBrightness}%", Color.rgb(255, 212, 103)),
            Triple("PC BRIDGE", if (st.connected) "ONLINE" else "OFFLINE", color)
        )
        cards.forEachIndexed { i, item ->
            val left = 48f + i * 258f
            val r = RectF(left, 205f, left + 232f, 330f)
            pFill.color = Color.argb(62, 255, 255, 255)
            c.drawRoundRect(r, 22f, 22f, pFill)
            c.drawText(item.first, r.left + 22f, r.top + 34f, dockText(item.third, 11f))
            c.drawText(item.second, r.left + 22f, r.top + 84f,
                dockText(Color.WHITE, 28f, false))
        }
        c.drawText("SWIPE FOR ANOTHER CARD  ·  0 BACK", 48f, 403f,
            dockText(Color.argb(200, 235, 245, 255), 12f))
    }

    private fun drawExpandedAgent(c: Canvas) {
        drawGeneratedIcon(c, st.nowKind.ifBlank { "chatgpt" }, RectF(43f, 42f, 157f, 156f))
        c.drawText("MANZANILLA AGENT", 181f, 63f, dockText(stateColor(st.nowState), 13f))
        val task = st.nowTask.ifBlank { "Ready for your next instruction" }
        val taskPaint = dockText(Color.WHITE, 28f, false)
        c.drawText(fitText(task, taskPaint, width - 217f), 181f, 111f, taskPaint)
        val state = st.nowState.ifBlank { "READY" }.uppercase().replace('_', ' ')
        val detail = listOf(state, st.nowProject, st.nowModel).filter { it.isNotBlank() }.joinToString("  ·  ")
        c.drawText(fitText(detail, dockText(Color.argb(210, 214, 232, 248), 14f), width - 217f),
            181f, 144f, dockText(Color.argb(210, 214, 232, 248), 14f))
        val pulse = 13f + 3f * sin(frame / 260.0).toFloat()
        pFill.color = stateColor(st.nowState)
        c.drawCircle(75f, 252f, pulse, pFill)
        c.drawText("LIVE STATUS", 110f, 260f, dockText(Color.WHITE, 18f, false))
        c.drawText(st.nowDetail.ifBlank { "Your PC agent is connected to this display." }, 110f, 300f,
            dockText(Color.argb(215, 218, 234, 250), 15f, false))
        c.drawText("CALL  TALK TO AGENT", 110f, 370f, dockText(Color.rgb(104, 234, 191), 15f))
        c.drawText("0  BACK", 110f, 410f, dockText(Color.argb(205, 255, 255, 255), 13f))
    }

    private fun drawOrbitHome(c: Canvas) {
        if (st.xmbCategory != xmbLastCategory || st.xmbItem != xmbLastItem) {
            xmbLastCategory = st.xmbCategory
            xmbLastItem = st.xmbItem
            xmbMovedAt = frame
        }
        val categories = XmbMenu.categories
        val selectedCategory = XmbMenu.category(st.xmbCategory)
        st.xmbItem = st.xmbItem.coerceIn(selectedCategory.items.indices)
        val motion = ((frame - xmbMovedAt) / 260f).coerceIn(0f, 1f)
        val settle = if (xmbMovedAt == 0L) 1f else
            (1.0 - exp(-8.5 * motion) * cos(13.0 * motion)).toFloat()

        // The classic cross-media bar: one horizontal category axis with the
        // chosen category opening into a vertical item axis.
        val anchorX = 238f
        val categoryY = 146f
        val spacing = 112f
        for (index in categories.indices) {
            var delta = index - st.xmbCategory
            if (delta > categories.size / 2) delta -= categories.size
            if (delta < -categories.size / 2) delta += categories.size
            val cx = anchorX + delta * spacing * settle
            if (cx !in -90f..(width + 90f)) continue
            val selected = delta == 0
            val size = if (selected) 74f else 52f
            val dest = RectF(cx - size / 2f, categoryY - size / 2f,
                cx + size / 2f, categoryY + size / 2f)
            drawXmbIcon(c, categories[index].icon, dest, selected)
            if (selected) {
                val categoryPaint = xmbText(Color.WHITE, 16f, true)
                c.drawText(categories[index].label, cx - categoryPaint.measureText(categories[index].label) / 2f,
                    categoryY - 51f, categoryPaint)
            }
            zone(RectF(dest.left - 14f, dest.top - 14f, dest.right + 14f, dest.bottom + 14f),
                "xmb_category:$index")
        }

        val itemX = anchorX
        val firstY = 238f
        val itemGap = 58f
        for ((index, item) in selectedCategory.items.withIndex()) {
            val y = firstY + (index - st.xmbItem) * itemGap * settle
            if (y !in 192f..420f) continue
            val selected = index == st.xmbItem
            val iconSize = if (selected) 54f else 38f
            val dest = RectF(itemX - iconSize / 2f, y - iconSize / 2f,
                itemX + iconSize / 2f, y + iconSize / 2f)
            if (selected) {
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(62, 255, 255, 255)
                    maskFilter = BlurMaskFilter(17f, BlurMaskFilter.Blur.NORMAL)
                }
                c.drawCircle(itemX, y, 37f, glow)
            }
            drawXmbIcon(c, item.icon, dest, selected)
            val label = xmbText(if (selected) Color.WHITE else Color.argb(176, 255, 255, 255),
                if (selected) 18f else 13f, selected)
            c.drawText(item.label, itemX + 48f, y + 6f, label)
            zone(RectF(itemX - 38f, y - 27f, width - 70f, y + 27f), "xmb_item:$index")
        }

        val state = st.nowState.ifBlank { "ready" }.uppercase().replace('_', ' ')
        val task = st.nowTask.ifBlank { "Your AI companion is ready" }
        val status = xmbText(Color.WHITE, 12f, false)
        c.drawText(fitText("$state  ·  $task", status, 390f), width - 414f, height - 52f, status)
        pFill.color = if (st.connected) Color.rgb(96, 230, 177) else Color.rgb(255, 167, 89)
        c.drawCircle(width - 26f, height - 56f, 4f, pFill)
    }

    private fun xmbText(color: Int, size: Float, bold: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) modernBold else modernFont
            setShadowLayer(3f, 0f, 1.5f, Color.argb(170, 0, 0, 0))
        }

    private fun drawXmbIcon(c: Canvas, icon: String, dest: RectF, selected: Boolean) {
        val ok = if (dockstationTheme) {
            drawGeneratedIcon(c, icon, dest)
        } else when (icon) {
            "pet" -> drawSheetIcon(c, orbitCoreIcons, 10, 4, 3, dest)
            "games" -> drawSheetIcon(c, orbitGamesIcon, 0, 1, 1, dest)
            "themes" -> drawSheetIcon(c, orbitThemesIcon, 0, 1, 1, dest)
            "store" -> drawGeneratedIcon(c, "files", dest)
            else -> drawGeneratedIcon(c, icon, dest)
        }
        if (!ok) GlassIcons.draw(c, "more", dest, frame, true)
        if (!selected) {
            pFill.color = Color.argb(52, 6, 20, 43)
            c.drawCircle(dest.centerX(), dest.centerY(), dest.width() * .46f, pFill)
        }
    }

    private fun drawOrbitPage(c: Canvas, page: Int, offsetX: Float, progress: Float, active: Boolean) {
        val tiles = tilePages[page.coerceIn(tilePages.indices)]
        val centersX = floatArrayOf(108f, 321f, 534f, 747f)
        val centersY = floatArrayOf(189f, 343f)
        val decay = exp(-5.2 * progress).toFloat()
        for ((i, tile) in tiles.withIndex()) {
            val selected = active && i == st.homeSel
            val focusRaw = if (selected && orbitFocusStartedAt > 0L)
                ((frame - orbitFocusStartedAt) / 470f).coerceIn(0f, 1f) else 1f
            val focusSpring = if (focusRaw >= 1f) 1f else
                (1.0 - exp(-8.0 * focusRaw) * cos(14.0 * focusRaw)).toFloat()
            val stagger = i * .37
            val shakeX = sin(progress * 21.0 - stagger).toFloat() * 13f * decay
            val shakeY = cos(progress * 17.0 - stagger).toFloat() * 7f * decay
            val cx = centersX[i % 4] + offsetX + shakeX
            val cy = centersY[i / 4] + shakeY - if (selected) 5f * focusSpring else 0f
            val radius = 49f + if (selected) 10f * focusSpring else 0f

            val pressScale = if (pressedZone == "tile:${tile.id}") .92f else 1f
            // The generated icon is now the hero object: it occupies the old
            // bubble footprint directly instead of sitting inside a second
            // circular container.
            val iconSize = (if (selected) 108f else 94f) * pressScale
            val iconDest = RectF(cx - iconSize / 2f, cy - iconSize / 2f - 2f,
                cx + iconSize / 2f, cy + iconSize / 2f - 2f)
            val rendered = if (dockstationTheme) {
                drawGeneratedIcon(c, if (tile.id == "clor") "claude" else tile.id, iconDest)
            } else when (tile.id) {
                "pet" -> drawSheetIcon(c, orbitCoreIcons, 10, 4, 3, iconDest)
                "games" -> drawSheetIcon(c, orbitGamesIcon, 0, 1, 1, iconDest)
                "themes" -> drawSheetIcon(c, orbitThemesIcon, 0, 1, 1, iconDest)
                "more" -> drawSheetIcon(c, orbitMoreIcon, 0, 1, 1, iconDest)
                else -> drawGeneratedIcon(c, if (tile.id == "clor") "claude" else tile.id, iconDest)
            }
            if (!rendered) GlassIcons.draw(c, "more", iconDest, frame, true)

            val labelBox = RectF(cx - 71f, cy + radius + 4f, cx + 71f, cy + radius + 29f)
            pFill.color = Color.argb(if (selected) 205 else 155, 4, 17, 38)
            c.drawRoundRect(labelBox, 13f, 13f, pFill)
            val label = glassHeadline(Color.WHITE, if (tile.label.length > 10) 9.2f else 10.5f)
            c.drawText(tile.label, cx - label.measureText(tile.label) / 2f,
                labelBox.centerY() + 3.7f, label)

            pFill.color = if (selected) th.yellow else Color.argb(230, 7, 25, 52)
            c.drawCircle(cx - radius + 7f, cy - radius + 8f, 10f, pFill)
            val badge = glassHeadline(if (selected) Color.rgb(18, 35, 56) else Color.WHITE, 8f)
            val badgeText = (i + 1).toString()
            c.drawText(badgeText, cx - radius + 7f - badge.measureText(badgeText) / 2f,
                cy - radius + 11f, badge)
            pFill.isAntiAlias = false
            if (active) zone(RectF(cx - radius - 12f, cy - radius - 12f,
                cx + radius + 12f, cy + radius + 32f), "tile:${tile.id}")
        }
    }

    private fun drawGlassHome(c: Canvas) {
        val tiles = tilePages[st.homePage]
        val tileW = 145f
        val tileH = 143f
        val gap = 30f
        val startX = (width - (tileW * 4f + gap * 3f)) / 2f
        val rows = floatArrayOf(112f, 275f)

        for ((i, tile) in tiles.withIndex()) {
            val x = startX + (i % 4) * (tileW + gap)
            val y = rows[i / 4]
            val r = RectF(x, y, x + tileW, y + tileH)
            val selected = i == st.homeSel
            panel(c, r, if (selected) th.yellow else th.panelLine, if (selected) 4.2f else 2.5f)
            zone(r, "tile:${tile.id}")

            // The reference uses a soft ceramic disc floating inside the lens.
            val discCx = r.centerX()
            val discCy = r.top + 58f
            val discShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(34, 25, 28, 30)
            }
            c.drawCircle(discCx + 1.5f, discCy + 5f, 39f, discShadow)
            val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (tile.icon == "claude") Color.rgb(252, 232, 232) else Color.rgb(253, 251, 244)
            }
            c.drawCircle(discCx, discCy, 38f, disc)
            val discRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
            }
            c.drawCircle(discCx, discCy, 38f, discRim)

            val normalized = normalizedIconId(if (tile.id == "clor") "claude" else tile.id)
            val iconDest = RectF(discCx - 27f, discCy - 27f, discCx + 27f, discCy + 27f)
            if (normalized in setOf("chatgpt", "codex", "claude", "wispr", "stream", "tasks", "files", "games") ||
                tile.id == "more") {
                GlassIcons.draw(c, if (tile.id == "more") "more" else normalized,
                    iconDest, frame, th.darkIconSurface)
            } else if (tile.icon.isNotEmpty()) drawGeneratedIcon(c, tile.icon, iconDest)

            val label = glassHeadline(th.fg, if (tile.label.length > 10) 10.5f else 11.5f)
            c.drawText(tile.label, r.centerX() - label.measureText(tile.label) / 2f, r.bottom - 17f, label)

            // The number badge teaches the physical keypad mapping without a
            // separate help screen: keys 1..8 activate the matching home tile.
            pFill.color = if (selected) th.accent else Color.argb(222, 255, 253, 247)
            c.drawCircle(r.left + 16f, r.top + 16f, 10f, pFill)
            val badge = glassHeadline(if (selected) Color.WHITE else th.accent, 7.5f)
            val badgeText = (i + 1).toString()
            c.drawText(badgeText, r.left + 16f - badge.measureText(badgeText) / 2f,
                r.top + 18.8f, badge)
        }

        val pageControl = RectF(width / 2f - 125f, height - 48f, width / 2f + 125f, height - 22f)
        zone(pageControl, "home_page_toggle")
        val pageLabel = if (st.homePage == 0) "[9]  PAGE 1 / 2   -/+ NEXT"
            else "[9]  PAGE 2 / 2   -/+ BACK"
        val pagePaint = glassHeadline(th.fg, 8.5f)
        c.drawText(pageLabel, pageControl.centerX() - pagePaint.measureText(pageLabel) / 2f,
            pageControl.centerY() + 3f, pagePaint)
    }

    private fun drawGames(c: Canvas) {
        val names = listOf("SUPER MARIO BROS. 3", "NASCAR RUMBLE")
        val systems = listOf("NINTENDO ENTERTAINMENT SYSTEM", "PLAYSTATION")
        val selected = st.gameSelected.coerceIn(names.indices)

        c.drawText("GAME", 48f, 92f, glassHeadline(th.fg, 23f))
        c.drawText("MEMORY STICK", 124f, 92f, glassHeadline(th.green, 9f))
        c.drawText("PSP-STYLE LIBRARY  /  LOCAL GAMES", 48f, 111f,
            glassHeadline(Color.argb(155, Color.red(th.fg), Color.green(th.fg), Color.blue(th.fg)), 8.8f))

        for (i in names.indices) {
            val offset = i - selected
            val cx = width / 2f + offset * 290f
            val w = if (offset == 0) 252f else 184f
            val h = if (offset == 0) 176f else 132f
            val top = 128f + if (offset == 0) 0f else 23f
            val r = RectF(cx - w / 2f, top, cx + w / 2f, top + h)

            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(38, 25, 28, 30) }
            c.drawRoundRect(RectF(r.left + 4f, r.top + 7f, r.right + 4f, r.bottom + 7f), 23f, 23f, shadow)
            pFill.color = if (i == 0) Color.rgb(243, 185, 35) else Color.rgb(35, 42, 49)
            c.drawRoundRect(r, 23f, 23f, pFill)
            c.drawRoundRect(r, 23f, 23f, strokePaint(if (offset == 0) th.yellow else Color.argb(110, 255, 255, 255),
                if (offset == 0) 3.5f else 1.5f))

            if (i == 0) drawMarioGameMark(c, r) else drawRacingGameMark(c, r)

            val strip = RectF(r.left, r.bottom - 43f, r.right, r.bottom)
            pFill.color = Color.argb(218, 17, 22, 28)
            c.drawRoundRect(strip, 0f, 0f, pFill)
            val title = glassHeadline(Color.WHITE, if (offset == 0) 11f else 8f)
            c.drawText(names[i], r.centerX() - title.measureText(names[i]) / 2f, r.bottom - 18f, title)
            zone(r, "game:$i")
        }

        val info = RectF(48f, 329f, width - 48f, 438f)
        panel(c, info, th.panelLine, 2f)
        c.drawText(names[selected], info.left + 21f, info.top + 27f, glassHeadline(th.fg, 14f))
        c.drawText(systems[selected], info.left + 21f, info.top + 47f, glassHeadline(th.green, 9f))
        c.drawText("1 UP  •  4 DOWN  •  5 LEFT  •  6 RIGHT", info.left + 21f, info.top + 67f,
            glassHeadline(th.fg, 8.7f))
        c.drawText(if (selected == 0) "TOP ACTION KEYS: B / A  •  8 SELECT  •  9 START"
            else "ACTION KEYS: TRIANGLE / CIRCLE / SQUARE / CROSS",
            info.left + 21f, info.top + 84f, glassHeadline(th.fg, 8.7f))
        c.drawText(if (selected == 0) "0 EXIT  •  2 LOAD  •  3 SAVE" else "8 SELECT  •  9 START  •  0 EXIT",
            info.left + 21f, info.top + 101f,
            glassHeadline(th.accent, 8.7f))

        val launch = RectF(info.right - 176f, info.top + 23f, info.right - 18f, info.bottom - 22f)
        pFill.color = th.green
        c.drawRoundRect(launch, 28f, 28f, pFill)
        center2(c, "5  PLAY", glassHeadline(Color.WHITE, 11f), launch, 4f)
        zone(launch, "game_launch")

        val prev = RectF(10f, 192f, 58f, 264f)
        val next = RectF(width - 58f, 192f, width - 10f, 264f)
        zone(prev, "game_prev"); zone(next, "game_next")
        c.drawText("‹", prev.centerX() - 8f, prev.centerY() + 10f, glassHeadline(th.fg, 30f))
        c.drawText("›", next.centerX() - 7f, next.centerY() + 10f, glassHeadline(th.fg, 30f))
    }

    private fun drawMarioGameMark(c: Canvas, r: RectF) {
        val cx = r.centerX(); val cy = r.top + 62f
        pFill.color = Color.rgb(184, 37, 35)
        c.drawRoundRect(RectF(cx - 49f, cy - 33f, cx + 49f, cy + 13f), 32f, 32f, pFill)
        pFill.color = Color.rgb(255, 246, 214)
        c.drawCircle(cx - 24f, cy + 5f, 11f, pFill); c.drawCircle(cx + 24f, cy + 5f, 11f, pFill)
        pFill.color = Color.WHITE
        c.drawCircle(cx, cy - 6f, 16f, pFill)
        val m = glassHeadline(Color.rgb(184, 37, 35), 14f)
        c.drawText("M", cx - m.measureText("M") / 2f, cy - 1f, m)
    }

    private fun drawRacingGameMark(c: Canvas, r: RectF) {
        val left = r.centerX() - 64f; val top = r.top + 25f; val s = 15f
        for (row in 0..4) for (col in 0..7) {
            pFill.color = if ((row + col) % 2 == 0) Color.WHITE else Color.rgb(195, 28, 33)
            c.drawRect(left + col * s, top + row * s, left + (col + 1) * s, top + (row + 1) * s, pFill)
        }
        c.save(); c.rotate(-11f, r.centerX(), r.centerY())
        pFill.color = Color.rgb(226, 49, 43)
        c.drawRoundRect(RectF(r.centerX() - 70f, r.top + 80f, r.centerX() + 70f, r.top + 114f), 10f, 10f, pFill)
        c.restore()
    }

    private fun drawAiWorkspace(c: Canvas) {
        if (liquidGlass) {
            drawGlassAiWorkspace(c)
            return
        }
        if (st.aiWorkspacePage >= 1) {
            drawAiWorkspaceShortcuts(c)
            return
        }
        val target = st.aiWorkspaceKind
        val targetName = if (target == "codex") "CODEX" else "CHATGPT"
        val running = st.aiRunning[target] == true || st.nowKind == target ||
            st.aiProjects.any { it.state == "working" }
        val header = RectF(18f, 62f, width - 18f, 106f)
        panel(c, header, if (running) th.green else th.panelLine, 3f)
        drawGeneratedIcon(c, target, RectF(29f, 67f, 63f, 101f))
        c.drawText("$targetName PROJECT CONSOLE", 75f, 89f, px(th.fg, 15f))
        val hp = px(if (running) th.green else th.gray, 10f)
        val hLabel = if (running) "LIVE" else "READY"
        c.drawText(hLabel, header.right - hp.measureText(hLabel) - 14f, 89f, hp)

        val current = st.aiProjects.firstOrNull()
        val task = st.nowTask.ifBlank { current?.task ?: "No active project" }
        val project = st.nowProject.ifBlank { current?.project.orEmpty() }
        val state = if (st.nowState !in listOf("", "idle")) st.nowState else current?.state ?: "idle"
        val detail = st.nowDetail.ifBlank { current?.detail.orEmpty() }
        val model = st.nowModel.ifBlank { current?.model.orEmpty() }

        val main = RectF(18f, 116f, 528f, 348f)
        panel(c, main, stateColor(state), 3f)
        c.drawText("CURRENT WORK", 34f, 140f, px(th.cyan, 10f))
        c.drawText(fitText(task.uppercase(), px(th.fg, 15f), main.width() - 32f), 34f, 170f, px(th.fg, 15f))
        if (project.isNotBlank()) c.drawText(fitText("PROJECT  $project".uppercase(), px(th.yellow, 9f), main.width() - 32f),
            34f, 194f, px(th.yellow, 9f))
        val statusLabel = state.uppercase().replace('_', ' ')
        c.drawText("STATE  $statusLabel", 34f, 221f, px(stateColor(state), 10f))
        if (model.isNotBlank()) c.drawText(fitText("AGENT  $model".uppercase(), px(th.pink, 9f), main.width() - 32f),
            245f, 221f, px(th.pink, 9f))
        // Activity is deliberately state-based; ChatGPT does not publish a trustworthy percentage.
        val blocks = 16
        val lane = RectF(34f, 238f, main.right - 18f, 256f)
        for (i in 0 until blocks) {
            val lit = when (state) {
                "done" -> true
                "working", "active" -> ((frame / 150 + i) % blocks) < 7
                "needs_input" -> blink && i % 2 == 0
                else -> i < 2
            }
            pFill.color = if (lit) stateColor(state) else Color.argb(90, 70, 86, 112)
            val bw = lane.width() / blocks
            c.drawRect(lane.left + i * bw, lane.top, lane.left + (i + 1) * bw - 3f, lane.bottom, pFill)
        }
        if (detail.isNotBlank()) {
            val dp = px(th.gray, 9f)
            c.drawText(fitText(detail.uppercase(), dp, main.width() - 32f), 34f, 281f, dp)
        }
        c.drawText("LIVE FROM PC BRIDGE", 34f, 326f, px(if (st.connected) th.green else th.accent, 9f))
        zone(main, "aiws_focus")

        val recent = RectF(540f, 116f, width - 18f, 348f)
        panel(c, recent, th.panelLine, 3f)
        c.drawText("RECENT PROJECTS", 554f, 140f, px(th.fg, 10f))
        val projects = st.aiProjects.take(3)
        if (projects.isEmpty()) {
            c.drawText("WAITING FOR PC", 554f, 183f, px(th.gray, 9f))
            c.drawText("PROJECT DATA", 554f, 202f, px(th.gray, 9f))
        } else projects.forEachIndexed { i, item ->
            val y = 167f + i * 58f
            pFill.color = stateColor(item.state)
            c.drawRect(554f, y - 9f, 560f, y - 2f, pFill)
            val rp = px(th.fg, 8f)
            c.drawText(fitText(item.task.uppercase(), rp, recent.width() - 34f), 568f, y, rp)
            val sub = listOf(item.project, item.model).filter { it.isNotBlank() }.joinToString(" / ")
            c.drawText(fitText(sub.uppercase(), px(th.gray, 7f), recent.width() - 28f), 568f, y + 18f, px(th.gray, 7f))
            c.drawText(item.state.uppercase().replace('_', ' '), 568f, y + 35f, px(stateColor(item.state), 7f))
        }

        val actions = listOf("REPLY VOICE" to "talk", "OPEN ON PC" to "focus", "SEND" to "send", "STOP" to "stop")
        val gap = 8f
        val aw = (width - 36f - gap * 3f) / 4f
        actions.forEachIndexed { i, (label, icon) ->
            val r = RectF(18f + i * (aw + gap), 360f, 18f + i * (aw + gap) + aw, 425f)
            panel(c, r, if (i == 1) th.pink else th.panelLine, 3f)
            drawActionIcon(c, icon, RectF(r.left + 12f, r.top + 9f, r.left + 49f, r.top + 46f))
            c.drawText("${i + 1} $label", r.left + 58f, r.centerY() + 5f, px(th.fg, 8f))
            zone(r, "aiws:${i + 1}")
        }
        val toggle = RectF(width / 2f - 115f, 433f, width / 2f + 115f, 464f)
        zone(toggle, "aiws_toggle")
        center(c, "- / +  WORK LOUDER CONTROLS    *  FOCUS PC    0  HOME", px(th.cyan, 8f), 452f)
    }

    private fun drawAiWorkspaceShortcuts(c: Canvas) {
        val targetName = if (st.aiWorkspaceKind == "codex") "CODEX" else "CHATGPT"
        val profile = AppProfiles.get(st.aiWorkspaceKind)
        val pageCount = maxOf(1, (profile?.controls?.size?.plus(8) ?: 9) / 9)
        center(c, "$targetName MICRO CONTROLLER  ${st.aiWorkspacePage}/$pageCount", px(th.fg, 13f), 82f)
        val controls = profile?.controls.orEmpty().drop((st.aiWorkspacePage - 1) * 9).take(9)
        val top = 96f
        val gap = 9f
        val cw = (width - 36f - gap * 2f) / 3f
        val ch = 101f
        controls.forEachIndexed { i, control ->
            val x = 18f + (i % 3) * (cw + gap)
            val y = top + (i / 3) * (ch + gap)
            val r = RectF(x, y, x + cw, y + ch)
            panel(c, r, if (i == st.homeSel && blink) th.yellow else th.panelLine, 3f)
            c.drawText("${i + 1}", r.left + 9f, r.top + 18f, px(th.yellow, 9f))
            drawActionIcon(c, control.id, RectF(r.centerX() - 25f, r.top + 10f, r.centerX() + 25f, r.top + 60f))
            labelStrip(c, r, 27f)
            val lp = px(th.fg, 9f)
            c.drawText(control.label, r.centerX() - lp.measureText(control.label) / 2f, r.bottom - 11f, lp)
            zone(r, "aiws:${i + 1}")
        }
        val toggle = RectF(18f, 430f, width - 18f, 464f)
        zone(toggle, "aiws_toggle")
        center(c, "- / +  STATUS / MORE    *  FOCUS $targetName    0  HOME", px(th.cyan, 8f), 451f)
    }

    private fun drawGlassAiWorkspace(c: Canvas) {
        // Keep the home screen visible as spatial context, then push and soften it
        // while the controller arrives from the right like the reference mockup.
        val progress = ((frame - transStart) / 330f).coerceIn(0f, 1f)
        val ease = 1f - (1f - progress) * (1f - progress) * (1f - progress)
        c.save()
        c.translate(-38f * ease, 0f)
        drawHome(c)
        drawHud(c)
        drawLegend(c)
        c.restore()
        hitZones.clear()

        pFill.color = Color.argb((72 * ease).toInt(), 45, 28, 12)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)

        val drawerRight = width - 24f
        val drawerLeft = drawerRight - 392f + (1f - ease) * 420f
        val drawer = RectF(drawerLeft, 20f, drawerRight, height - 18f)
        drawLiquidGlassPanel(c, drawer, Color.rgb(241, 191, 0), 2.4f)

        val kind = st.aiWorkspaceKind.lowercase()
        val title = when (kind) {
            "codex" -> "CODEX"
            "claude", "clor" -> "CLAUDE"
            else -> "CHATGPT"
        }
        val iconKind = when (kind) {
            "claude", "clor" -> "claude"
            "codex" -> "codex"
            else -> "chatgpt"
        }
        val iconDisc = RectF(drawer.left + 26f, drawer.top + 20f, drawer.left + 86f, drawer.top + 80f)
        pFill.color = Color.rgb(255, 252, 245)
        c.drawCircle(iconDisc.centerX(), iconDisc.centerY(), 30f, pFill)
        c.drawCircle(iconDisc.centerX(), iconDisc.centerY(), 30f,
            strokePaint(Color.argb(155, 255, 255, 255), 1.2f))
        GlassIcons.draw(c, iconKind, iconDisc.insetCopy(7f), frame, th.darkIconSurface)
        c.drawText(title, drawer.left + 99f, drawer.top + 45f,
            glassHeadline(Color.rgb(47, 47, 47), 22f))
        c.drawText("ACTIVE  •  MANZANILLA PROJECT", drawer.left + 99f, drawer.top + 67f,
            glassHeadline(Color.rgb(0, 168, 89), 10f))

        val close = RectF(drawer.right - 56f, drawer.top + 19f, drawer.right - 20f, drawer.top + 55f)
        pFill.color = Color.argb(178, 255, 253, 248)
        c.drawCircle(close.centerX(), close.centerY(), 18f, pFill)
        c.drawLine(close.centerX() - 6f, close.centerY() - 6f,
            close.centerX() + 6f, close.centerY() + 6f, strokePaint(Color.rgb(52, 52, 52), 1.7f))
        c.drawLine(close.centerX() + 6f, close.centerY() - 6f,
            close.centerX() - 6f, close.centerY() + 6f, strokePaint(Color.rgb(52, 52, 52), 1.7f))
        zone(close, "go_home")

        val open = RectF(drawer.left + 25f, drawer.top + 91f, drawer.right - 25f, drawer.top + 139f)
        pFill.color = Color.rgb(0, 168, 89)
        c.drawRoundRect(open, 24f, 24f, pFill)
        val openText = "↗  OPEN $title APP"
        val openPaint = glassHeadline(Color.WHITE, 12.5f)
        c.drawText(openText, open.centerX() - openPaint.measureText(openText) / 2f,
            open.centerY() + 4f, openPaint)
        zone(open, "aiws_focus")

        val matchingLiveTask = if (st.nowKind.equals(kind, ignoreCase = true)) st.nowTask else ""
        val task = matchingLiveTask.ifBlank {
            st.aiProjects.firstOrNull()?.task?.takeIf { it.isNotBlank() }
                ?: if (title == "CODEX") "Building Manzanilla interface" else "Working on Liquid Glass UI"
        }
        val taskCard = RectF(drawer.left + 25f, drawer.top + 156f, drawer.right - 25f, drawer.top + 207f)
        drawGlassInsetCard(c, taskCard)
        pFill.color = Color.rgb(0, 168, 89)
        c.drawCircle(taskCard.left + 22f, taskCard.top + 18f, 5f, pFill)
        c.drawText(fitText(task, glassHeadline(Color.rgb(52, 52, 52), 10.5f), taskCard.width() - 72f),
            taskCard.left + 40f, taskCard.top + 22f, glassHeadline(Color.rgb(52, 52, 52), 12f))
        c.drawText(if (st.aiProjects.isEmpty()) "Ready for a new task" else "${st.aiProjects.size} tasks running",
            taskCard.left + 40f, taskCard.top + 42f, glassHeadline(Color.rgb(112, 104, 92), 9.5f))
        c.drawText("•••", taskCard.right - 43f, taskCard.top + 31f,
            glassHeadline(Color.rgb(80, 76, 70), 10f))

        val effort = RectF(drawer.left + 25f, drawer.top + 221f, drawer.right - 25f, drawer.top + 270f)
        drawGlassInsetCard(c, effort)
        val effortLabels = listOf("LOW", "MEDIUM", "HIGH")
        effortLabels.forEachIndexed { i, label ->
            val segment = RectF(effort.left + 7f + i * (effort.width() - 14f) / 3f,
                effort.top + 7f, effort.left + 7f + (i + 1) * (effort.width() - 14f) / 3f,
                effort.bottom - 7f)
            if (i == 2) c.drawRoundRect(segment, 18f, 18f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(35, 170, 21, 27)
                    style = Paint.Style.FILL
                })
            if (i == 2) c.drawRoundRect(segment, 18f, 18f,
                strokePaint(Color.rgb(170, 21, 27), 1.3f))
            pFill.color = listOf(Color.rgb(0, 168, 89), Color.rgb(241, 191, 0), Color.rgb(170, 21, 27))[i]
            c.drawCircle(segment.left + 15f, segment.centerY(), 4.2f, pFill)
            c.drawText(label, segment.left + 26f, segment.centerY() + 3.5f,
                glassHeadline(Color.rgb(58, 58, 58), 9.5f))
        }

        val model = RectF(drawer.left + 25f, drawer.top + 283f, drawer.right - 25f, drawer.top + 327f)
        drawGlassInsetCard(c, model)
        c.drawText("MODEL", model.left + 18f, model.centerY() + 4f,
            glassHeadline(Color.rgb(64, 61, 57), 11f))
        c.drawText(st.nowModel.ifBlank { if (title == "CLAUDE") "CLAUDE" else "GPT-5" },
            model.left + 83f, model.centerY() + 4f, glassHeadline(Color.rgb(64, 61, 57), 11f))
        c.drawText("›", model.right - 25f, model.centerY() + 5f,
            glassHeadline(Color.rgb(80, 78, 72), 17f))

        val labels = listOf("1  REPLY", "2  OPEN PC", "3  SEND", "4  STOP")
        val icons = listOf("wispr", "chatgpt", "send", "stop")
        val actionGap = 12f
        val actionW = (drawer.width() - 50f - actionGap) / 2f
        labels.forEachIndexed { i, label ->
            val col = i % 2
            val row = i / 2
            val left = drawer.left + 25f + col * (actionW + actionGap)
            val r = RectF(left, drawer.top + 337f + row * 55f,
                left + actionW, drawer.top + 383f + row * 55f)
            drawGlassInsetCard(c, r)
            val disc = RectF(r.left + 10f, r.top + 7f, r.left + 48f, r.top + 45f)
            pFill.color = Color.rgb(255, 252, 245)
            c.drawCircle(disc.centerX(), disc.centerY(), 19f, pFill)
            if (icons[i] in setOf("wispr", "tasks", "more"))
                GlassIcons.draw(c, icons[i], disc.insetCopy(5f), frame, th.darkIconSurface)
            else {
                pFill.color = Color.rgb(170, 21, 27)
                c.drawRoundRect(RectF(disc.centerX() - 7f, disc.centerY() - 7f,
                    disc.centerX() + 7f, disc.centerY() + 7f), 2f, 2f, pFill)
            }
            c.drawText(label, r.left + 57f, r.centerY() + 4f,
                glassHeadline(Color.rgb(54, 54, 54), 10f))
            zone(r, "aiws:${i + 1}")
        }
    }

    private fun RectF.insetCopy(amount: Float) =
        RectF(left + amount, top + amount, right - amount, bottom - amount)

    private fun drawGlassInsetCard(c: Canvas, r: RectF) {
        pFill.color = Color.argb(158, 255, 252, 245)
        c.drawRoundRect(r, 19f, 19f, pFill)
        c.drawRoundRect(r, 19f, 19f, strokePaint(Color.argb(165, 255, 255, 255), 1.1f))
        c.drawLine(r.left + 16f, r.top + 1.5f, r.right - 16f, r.top + 1.5f,
            strokePaint(Color.argb(185, 255, 255, 255), 1.1f))
    }

    // ---- DASHBOARD: parallel agents + externals -----------------------------

    private fun drawDashboard(c: Canvas) {
        center(c, "* PARALLEL AGENTS *", px(th.fg, 18f), 82f)
        val cards = ArrayList<Triple<String, String, String>>() // kind,state,desc + slot in id
        val zoneIds = ArrayList<String>()
        for (s in st.slots.filter { it.state != "empty" }.take(5)) {
            cards.add(Triple(s.kind, s.state,
                s.title.ifEmpty { s.tail.takeLast(48).substringAfterLast('\n') }))
            zoneIds.add("slot:${s.slot}")
        }
        if (st.nowKind == "cowork") { cards.add(Triple("cowork", "working", st.nowName)); zoneIds.add("noop") }
        cards.add(Triple("", "", "")) // ADD card
        zoneIds.add("add")

        val cols = 3
        val cw = (width - 36) / cols.toFloat()
        val ch = (height - 150) / 2f
        for ((i, card) in cards.take(6).withIndex()) {
            val (kind, state, desc) = card
            val r = RectF(18 + (i % cols) * cw + 6, 96f + (i / cols) * (ch + 8),
                18 + (i % cols) * cw + cw - 6, 96f + (i / cols) * (ch + 8) + ch)
            if (kind.isEmpty()) {
                dashedBox(c, r, th.cyan)
                center2(c, "+", px(th.cyan, 30f), r, -8f)
                center2(c, "ADD AGENT", px(th.cyan, 11f), r, 26f)
                zone(r, "add")
            } else {
                panel(c, r)
                mascotFor(kind)?.let { sprite(c, it, r.left + 10, r.top + 10, 3.6f) }
                c.drawText(agentName(kind), r.left + 62, r.top + 28, px(th.fg, 14f))
                if (state != "needs_input" || blink)
                    c.drawText(stateLabel(state), r.left + 62, r.top + 48, px(stateColor(state), 11f))
                c.drawText(desc.take(20).uppercase(), r.left + 10, r.bottom - 14, px(th.gray, 9f))
                pFill.color = stateColor(state)
                c.drawRect(r.left + 6, r.bottom - 8f, r.right - 6, r.bottom - 5f, pFill)
                zone(r, zoneIds[i])
            }
        }
        center(c, "- / + = FILES   0 = HOME", px(th.cyan, 10f), height - 46f)
    }

    private fun center2(c: Canvas, text: String, p: Paint, r: RectF, dy: Float) =
        c.drawText(text, r.centerX() - p.measureText(text) / 2, r.centerY() + dy, p)

    private fun drawFiles(c: Canvas) {
        center(c, "* FILES *", px(th.fg, 18f), 82f)
        if (st.files.isEmpty()) {
            center(c, "NO FILES YET", px(th.gray, 16f), height * 0.42f)
            center(c, "DROP FILES ON THE PC CONSOLE", px(th.cyan, 12f), height * 0.52f)
        } else {
            var y = 112f
            for ((name, size) in st.files.take(8)) {
                val r = RectF(20f, y - 22f, width - 20f, y + 8f)
                panel(c, r, thick = 3f)
                c.drawText(name.take(32).uppercase(), 32f, y, px(th.fg, 12f))
                val kb = "${size / 1024}KB"
                c.drawText(kb, width - 32f - px(th.green, 11f).measureText(kb), y, px(th.green, 11f))
                y += 38f
            }
        }
        center(c, "- / + = AGENTS   0 = HOME", px(th.cyan, 10f), height - 46f)
    }

    // ---- AGENT view ---------------------------------------------------------

    private fun drawAgent(c: Canvas) {
        val s = st.sel()
        mascotFor(s.kind)?.let { sprite(c, it, 14f, 62f, 4f) }
        c.drawText("${agentName(s.kind).ifEmpty { "SLOT" }} ${s.slot}", 70f, 86f, px(th.fg, 19f))
        if (s.state != "needs_input" || blink)
            c.drawText(stateLabel(s.state), 70f, 108f, px(stateColor(s.state), 13f))

        val r = RectF(12f, 120f, width - 12f, height - 48f)
        panel(c, r)
        val p = mono(th.term, 20f)
        val text = s.tail.ifEmpty { "READY. LONG-PRESS ${s.slot} FOR NEW AGENT." }
        val lines = wrap(text, p, (r.width() - 24).toInt())
        var y = r.top + 26f
        val maxLines = ((r.height() - 36) / 24).toInt()
        for (ln in lines.takeLast(maxLines)) { c.drawText(ln, r.left + 12, y, p); y += 24f }
        if (blink) c.drawText("█", r.left + 12 + p.measureText(lines.lastOrNull() ?: ""), y - 24f, p)
    }

    // ---- VOICE: live dictation stage ---------------------------------------

    private fun drawVoice(c: Canvas) {
        if (voiceMode == "live") {
            val stop = liveVoiceScene.draw(c,width,height,frame,liveStatus,voiceLine,st.pttStart,
                livePreview,!android.animation.ValueAnimator.areAnimatorsEnabled(),liveCompanionReady,liveCompanionFailed,
                resources.displayMetrics.density,resources.configuration.fontScale)
            zone(stop,if(livePreview) "voice_preview_close" else if(voiceCapturing) "voice_stop" else "go_back")
            return
        }
        val live = voiceMode == "live"
        val direct = voiceMode == "global"
        val target = if (live) "ORANGE SPEAKER" else if (direct) "ACTIVE PC APP" else "CHATGPT"
        val accent = if (direct) th.pink else th.cyan
        c.drawText(if (live) "DAN / LIVE" else if (direct) "WISPR DICTATION" else "REPLY TO CHATGPT",
            42f, 76f, dockText(Color.WHITE, 24f, false))
        c.drawText("LAPTOP MICROPHONE  ·  $target", 43f, 101f,
            dockText(accent, 10f))

        // A lightweight echo of the Dockstation wallpaper: a few calm lines
        // react to capture without a mascot, orbit, or decorative control stage.
        for (lane in 0 until 7) {
            val path = Path()
            val baseY = 126f + lane * 37f
            var x = -10f
            while (x <= width + 10f) {
                val amplitude = if (live && liveStatus == "Speaking") 12f + lane else if (voiceCapturing) 7f + lane * .75f else 1.5f
                val y = baseY + sin(frame / (520.0 + lane * 65.0) + x / 72.0 + lane).toFloat() * amplitude
                if (x < 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 14f
            }
            c.drawPath(path, strokePaint(Color.argb(42 + lane * 7, 105, 218, 255), 1.2f))
        }

        val secs = ((frame - st.pttStart) / 1000).coerceAtLeast(0)
        val voiceState = when {
            live -> liveStatus.uppercase()
            voiceReady -> "READY TO SEND"
            voiceCapturing -> if (blink) "LISTENING" else "LISTENING."
            else -> "PROCESSING TRANSCRIPT"
        }
        val statePaint = dockText(if (voiceReady) th.green else if (voiceCapturing) th.yellow else accent,
            12f, false)
        c.drawText(voiceState, width - 45f - statePaint.measureText(voiceState), 73f, statePaint)
        val timer = "%02d:%02d".format(secs / 60, secs % 60)
        c.drawText(timer, width - 45f - statePaint.measureText(timer), 100f,
            dockText(Color.WHITE, 12f, false))

        val transcriptPanel = RectF(42f, 126f, width - 42f, 350f)
        pFill.color = Color.argb(150, 2, 16, 36)
        c.drawRoundRect(transcriptPanel, 24f, 24f, pFill)
        c.drawRoundRect(transcriptPanel, 24f, 24f, strokePaint(Color.argb(125, 105, 218, 255), 1.4f))
        c.drawText(if (live) "CONVERSATION + TASK PROGRESS" else if (direct) "DIRECT DICTATION" else "TRANSCRIPT", transcriptPanel.left + 24f,
            transcriptPanel.top + 31f, dockText(accent, 10f))
        val transcript = voiceLine.trim().ifEmpty {
            if (live) "Ask Dan to research a company and prepare a partnership email draft."
            else if (direct) "Your words are being typed into the active PC app."
            else if (voiceCapturing) "Speak now…" else "Finishing your transcript…"
        }
        val tp = dockText(if (voiceLine.isEmpty()) Color.argb(205, 217, 232, 247) else Color.WHITE,
            20f, false)
        var ty = transcriptPanel.top + 71f
        for (line in wrapWords(transcript, tp, (transcriptPanel.width() - 48f).toInt()).take(6)) {
            c.drawText(line, transcriptPanel.left + 24f, ty, tp)
            ty += 28f
        }

        if (voiceCapturing) {
            val stop = RectF(width / 2f - 170f, 376f, width / 2f + 170f, 432f)
            pFill.color = if (live) Color.rgb(245, 106, 105) else th.green
            c.drawRoundRect(stop, 18f, 18f, pFill)
            val stopLabel = if (live) "5  END CALL" else if (direct) "5  STOP DICTATION" else "5  STOP & REVIEW"
            val stopPaint = dockText(Color.rgb(2, 24, 26), 14f, false)
            c.drawText(stopLabel, stop.centerX() - stopPaint.measureText(stopLabel) / 2f,
                stop.centerY() + 5f, stopPaint)
            zone(stop, "voice_stop")
        }
        if (voiceReady) {
            val gap = 12f
            val actionW = (width - 84f - gap * 2f) / 3f
            val actions = listOf("1 AGAIN" to "voice_again", "5 SEND" to "voice_send", "0 CANCEL" to "voice_cancel")
            actions.forEachIndexed { i, (label, action) ->
                val r = RectF(42f + i * (actionW + gap), 376f,
                    42f + i * (actionW + gap) + actionW, 432f)
                pFill.color = when (action) {
                    "voice_send" -> th.green
                    "voice_cancel" -> th.accent
                    else -> th.cyan
                }
                c.drawRoundRect(r, 18f, 18f, pFill)
                val ap = dockText(Color.rgb(2, 22, 29), 13f, false)
                c.drawText(label, r.centerX() - ap.measureText(label) / 2f, r.centerY() + 5f, ap)
                zone(r, action)
            }
        }
        center(c, if (live) "GREEN TO START / END  ·  RED TO HANG UP  ·  0 BACK" else if (voiceReady) "CHECK THE WORDS BEFORE SENDING" else
            if (voiceCapturing) "PRESS 5 OR TAP THE GREEN BUTTON" else "PLEASE WAIT",
            dockText(Color.argb(205, 229, 240, 252), 9f), 459f)
    }

    // ---- TASKS / attention center ------------------------------------------

    private fun drawTasks(c: Canvas) {
        center(c, "* TASKS + ACTIVITY *", px(th.fg, 17f), 82f)
        var y = 104f
        val attention = st.slots.filter { it.state in listOf("needs_input", "error", "done") }
        if (attention.isEmpty() && st.events.isEmpty()) {
            center(c, "ALL QUIET - NOTHING NEEDS YOU", px(th.green, 13f), height * 0.4f)
        }
        for (s in attention.take(3)) {
            val r = RectF(18f, y, width - 18f, y + 52f)
            panel(c, r, stateColor(s.state))
            mascotFor(s.kind)?.let { sprite(c, it, r.left + 8, r.top + 8, 2.6f) }
            c.drawText("${agentName(s.kind)} ${stateLabel(s.state)}", r.left + 52, r.top + 24, px(stateColor(s.state), 12f))
            c.drawText(if (s.state == "needs_input") "TAP CALL=YES  END=NO  OR TAP HERE"
                else s.title.take(30).uppercase(), r.left + 52, r.top + 43, px(th.gray, 9f))
            zone(r, "slot:${s.slot}")
            y += 60f
        }
        // activity stream
        c.drawText("ACTIVITY STREAM", 22f, y + 18, px(th.cyan, 12f))
        y += 34f
        for ((time, who, text) in st.events.take(6)) {
            c.drawText(time, 22f, y, px(th.yellow, 10f))
            c.drawText(who, 92f, y, px(th.pink, 10f))
            c.drawText(text.take(38).uppercase(), 175f, y, px(th.fg, 10f))
            y += 26f
            if (y > height - 60) break
        }
    }

    // ---- STREAM DECK mode ---------------------------------------------------

    private fun drawStream(c: Canvas) {
        if (dockstationTheme) {
            drawDockstationStream(c)
            return
        }
        val pages = com.agentdeck.StreamDeckDefaults.pages(st.sdPagesJson)
        val pageCount = maxOf(1, pages.length())
        val pi = st.sdPage.coerceIn(0, pageCount - 1)
        val page = if (pages.length() > 0) pages.getJSONObject(pi) else null
        val pageName = page?.optString("name", "PAGE ${pi + 1}") ?: "NO PAGES"

        val r = RectF(width / 2f - 190, 62f, width / 2f + 190, 96f)
        panel(c, r, th.yellow, 3f)
        center(c, "STREAM DECK: $pageName  ${pi + 1}/$pageCount", px(th.yellow, 13f), 85f)

        val keys = page?.optJSONObject("keys")
        val cols = 3
        val cw = (width - 60) / cols.toFloat()
        val ch = (height - 210) / 3f
        for (i in 0 until 9) {
            val d = i + 1
            val k = keys?.optJSONObject("$d")
            val x = 30 + (i % cols) * cw + 5
            val y = 106f + (i / cols) * (ch + 6)
            val rr = RectF(x, y, x + cw - 10, y + ch)
            panel(c, rr, if (k != null) th.panelLine else Color.argb(90, 128, 128, 128), 3f)
            c.drawText("$d", rr.left + 10, rr.top + 24, px(th.yellow, 14f))
            val label = k?.optString("label", "") ?: ""
            if (label.isNotEmpty()) {
                val value = k?.optString("value", "") ?: ""
                val match = k?.optString("match", "") ?: ""
                val iconDest = RectF(rr.centerX() - 22f, rr.top + 4f, rr.centerX() + 22f, rr.top + 48f)
                if (!drawGeneratedIcon(c, "$label $value $match", iconDest)) {
                    drawActionIcon(c, "$label $value", iconDest)
                }
                labelStrip(c, rr, 29f)
                val labelPaint = px(th.fg, 9f)
                val shortLabel = label.take(14).uppercase()
                c.drawText(shortLabel, rr.centerX() - labelPaint.measureText(shortLabel) / 2f,
                    rr.bottom - 17f, labelPaint)
                val holdPaint = px(th.gray, 6f)
                c.drawText("HOLD: CONTROLS", rr.centerX() - holdPaint.measureText("HOLD: CONTROLS") / 2f,
                    rr.bottom - 5f, holdPaint)
            } else center2(c, "-", px(th.gray, 12f), rr, 6f)
            zone(rr, "sd:$d")
        }
        val themeKey = RectF(24f, height - 58f, 240f, height - 30f)
        val appsKey = RectF(width - 240f, height - 58f, width - 24f, height - 30f)
        panel(c, themeKey, th.pink, 2f)
        panel(c, appsKey, th.cyan, 2f)
        center2(c, "*  WINDOWS THEME", px(th.pink, 8f), themeKey, 3f)
        center2(c, "#  OPEN APPS", px(th.cyan, 8f), appsKey, 3f)
        zone(themeKey, "stream_theme")
        zone(appsKey, "stream_apps")
        center(c, "-/+ PAGE   0 HOME", px(th.gray, 8f), height - 67f)
    }

    private fun drawDockstationStream(c: Canvas) {
        val pages = com.agentdeck.StreamDeckDefaults.pages(st.sdPagesJson)
        val pageCount = maxOf(1, pages.length())
        val pi = st.sdPage.coerceIn(0, pageCount - 1)
        val page = if (pages.length() > 0) pages.getJSONObject(pi) else null
        val pageName = page?.optString("name", "PAGE ${pi + 1}") ?: "NO PAGES"
        val keys = page?.optJSONObject("keys")

        c.drawText("STREAM DECK", 86f, 82f, dockText(Color.WHITE, 20f))
        c.drawText("${pageName.uppercase()}  ·  ${pi + 1} / $pageCount", 86f, 105f,
            dockText(Color.rgb(112, 220, 255), 9f))
        val theme = dockText(Color.rgb(255, 151, 184), 9f)
        val apps = dockText(Color.rgb(112, 220, 255), 9f)
        c.drawText("*  WINDOWS THEME", width - 360f, 82f, theme)
        c.drawText("#  OPEN APPS", width - 177f, 82f, apps)
        zone(RectF(width - 382f, 54f, width - 206f, 104f), "stream_theme")
        zone(RectF(width - 199f, 54f, width - 18f, 104f), "stream_apps")

        val centersX = floatArrayOf(150f, 427f, 704f)
        val centersY = floatArrayOf(154f, 268f, 382f)
        for (i in 0 until 9) {
            val d = i + 1
            val k = keys?.optJSONObject("$d")
            val cx = centersX[i % 3]
            val cy = centersY[i / 3]
            val label = k?.optString("label", "") ?: ""
            val phase = sin(frame / 730.0 + i * .71).toFloat()
            val pressed = pressedZone == "sd:$d"
            val size = (if (pressed) 66f else 72f) + phase * 1.5f
            if (pressed) {
                val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(105, 106, 226, 255)
                    maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
                }
                c.drawCircle(cx, cy, 43f, glow)
            }
            if (k != null) {
                val value = k.optString("value", "")
                val match = k.optString("match", "")
                val dest = RectF(cx - size / 2f, cy - size / 2f - 8f,
                    cx + size / 2f, cy + size / 2f - 8f)
                if (!drawGeneratedIcon(c, "$label $value $match", dest)) {
                    drawActionIcon(c, "$label $value", dest)
                }
            }
            val number = dockText(Color.rgb(255, 219, 111), 8f)
            c.drawText(d.toString(), cx - 53f, cy - 42f, number)
            val lp = dockText(if (k == null) Color.argb(95, 255, 255, 255) else Color.WHITE, 10f)
            val text = if (label.isBlank()) "UNASSIGNED" else label.take(18).uppercase()
            c.drawText(text, cx - lp.measureText(text) / 2f, cy + 47f, lp)
            zone(RectF(cx - 84f, cy - 53f, cx + 84f, cy + 60f), "sd:$d")
        }
        val help = dockText(Color.argb(190, 225, 240, 255), 9f)
        val helpText = "− / +   PAGE       HOLD   APP CONTROLS       0   BACK"
        c.drawText(helpText, width / 2f - help.measureText(helpText) / 2f, height - 13f, help)
    }

    /** Large paged PC switcher, opened with # while Stream Deck is active. */
    private fun drawAppSwitcher(c: Canvas) {
        c.save()
        c.translate(-155f, 0f)
        drawStream(c)
        c.restore()
        hitZones.clear()
        pFill.color = Color.argb(145, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)

        // About 70% of the small display: enough room for two useful app cards per row.
        val drawer = RectF(width * 0.28f, 14f, width - 12f, height - 14f)
        pFill.color = Color.argb(249, 5, 18, 42)
        c.drawRoundRect(drawer, 18f, 18f, pFill)
        c.drawRoundRect(drawer, 18f, 18f, strokePaint(th.cyan, 3f))
        c.drawText("OPEN WINDOWS", drawer.left + 20f, drawer.top + 30f, px(th.fg, 17f))
        val close = RectF(drawer.right - 66f, drawer.top + 9f, drawer.right - 12f, drawer.top + 39f)
        panel(c, close, th.pink, 2f)
        center2(c, "# X", px(th.pink, 9f), close, 3f)
        zone(close, "appswitch_close")
        c.drawText("1-8 SWITCH  •  -/+ PAGE", drawer.left + 20f, drawer.top + 50f, px(th.gray, 8f))

        val pageSize = 8
        val pages = maxOf(1, (st.pcAppWindows.size + pageSize - 1) / pageSize)
        st.appSwitcherPage = st.appSwitcherPage.coerceIn(0, pages - 1)
        if (st.pcAppWindows.isEmpty()) {
            center2(c, if (st.connected) "READING WINDOWS..." else "BRIDGE OFFLINE",
                px(if (st.connected) th.yellow else th.pink, 12f), drawer, 4f)
        } else {
            val top = drawer.top + 62f
            val side = 13f
            val colGap = 9f
            val rowGap = 7f
            val cardW = (drawer.width() - side * 2f - colGap) / 2f
            val cardH = (drawer.height() - 111f - rowGap * 3f) / 4f
            for (i in 0 until pageSize) {
                val app = st.pcAppWindows.getOrNull(st.appSwitcherPage * pageSize + i)
                val col = i % 2
                val row = i / 2
                val x = drawer.left + side + col * (cardW + colGap)
                val y = top + row * (cardH + rowGap)
                val card = RectF(x, y, x + cardW, y + cardH)
                panel(c, card, when {
                    app == null -> Color.argb(90, 128, 128, 128)
                    app.active -> th.green
                    else -> th.panelLine
                }, 2.4f)
                c.drawText("${i + 1}", card.left + 9f, card.top + 17f, px(th.yellow, 11f))
                if (app != null) {
                    val iconSize = minOf(46f, card.height() - 12f)
                    val icon = RectF(card.left + 30f, card.centerY() - iconSize / 2f,
                        card.left + 30f + iconSize, card.centerY() + iconSize / 2f)
                    if (!drawGeneratedIcon(c, "${app.name} ${app.exe}", icon))
                        drawActionIcon(c, "focus", icon)
                    val textX = card.left + 84f
                    val textW = card.right - textX - 11f
                    c.drawText(fitText(app.name.uppercase(), px(th.fg, 10f), textW),
                        textX, card.centerY() - 5f, px(th.fg, 10f))
                    c.drawText(fitText(app.title, px(th.gray, 6.5f), textW),
                        textX, card.centerY() + 13f, px(th.gray, 6.5f))
                    if (app.active) {
                        pFill.color = th.green
                        c.drawCircle(card.right - 10f, card.top + 10f, 3.5f, pFill)
                    }
                    zone(card, "appswitch:${i + 1}")
                }
            }
        }

        val prev = RectF(drawer.left + 14f, drawer.bottom - 34f, drawer.left + 108f, drawer.bottom - 7f)
        val next = RectF(drawer.right - 108f, drawer.bottom - 34f, drawer.right - 14f, drawer.bottom - 7f)
        panel(c, prev, th.cyan, 2f); panel(c, next, th.cyan, 2f)
        center2(c, "- PREV", px(th.cyan, 8f), prev, 3f)
        center2(c, "+ NEXT", px(th.cyan, 8f), next, 3f)
        zone(prev, "appswitch_prev"); zone(next, "appswitch_next")
        val pageText = "PAGE ${st.appSwitcherPage + 1}/$pages   •   #/0 EXIT"
        center(c, pageText, px(th.yellow, 7f), drawer.bottom - 15f)
    }

    /** App-specific physical controller opened by holding a Stream Deck key. */
    private fun drawAppController(c: Canvas) {
        if (liquidGlass) {
            drawGlassAppControllerCoach(c)
            return
        }
        val profile = AppProfiles.get(st.appControllerId)
        if (profile == null) {
            center(c, "NO CONTROLLER FOR THIS APP", px(th.gray, 14f), height * 0.45f)
            return
        }
        val pages = maxOf(1, (profile.controls.size + 8) / 9)
        val page = st.appControllerPage.coerceIn(0, pages - 1)
        val header = RectF(24f, 62f, width - 24f, 98f)
        panel(c, header, th.yellow, 3f)
        val appDest = RectF(header.left + 6f, header.top - 4f, header.left + 48f, header.top + 38f)
        drawGeneratedIcon(c, st.appControllerId, appDest)
        c.drawText(profile.title, header.left + 58f, header.top + 24f, px(th.fg, 13f))
        val pageText = "PAGE ${page + 1}/$pages"
        c.drawText(pageText, header.right - 14f - px(th.yellow, 11f).measureText(pageText),
            header.top + 24f, px(th.yellow, 11f))

        val cols = 3
        val cw = (width - 52f) / cols
        val top = 108f
        val ch = (height - top - 102f) / 3f
        for (i in 0 until 9) {
            val controlIndex = page * 9 + i
            val control = profile.controls.getOrNull(controlIndex)
            val x = 26f + (i % cols) * cw
            val y = top + (i / cols) * ch
            val rr = RectF(x + 4f, y + 3f, x + cw - 4f, y + ch - 4f)
            panel(c, rr, if (control == null) th.gray else th.panelLine, 3f)
            c.drawText("${i + 1}", rr.left + 8f, rr.top + 18f, px(th.yellow, 10f))
            if (control != null) {
                val iconDest = RectF(rr.centerX() - 25f, rr.top + 4f, rr.centerX() + 25f, rr.top + 54f)
                val webIndex = webIconMap[control.id]
                if (webIndex != null) drawSheetIcon(c,
                    themedSheet(webIcons, webIconsDark), webIndex, 3, 3, iconDest)
                else if (control.id == "url_onedrive") drawOneDriveIcon(c, iconDest)
                else drawActionIcon(c, control.id, iconDest)
                labelStrip(c, rr, 25f)
                center2(c, control.label.take(16), px(th.fg, 8f), rr, 31f)
                zone(rr, "appctl:${i + 1}")
            }
        }
        val prev = RectF(24f, height - 92f, 180f, height - 66f)
        val next = RectF(width - 180f, height - 92f, width - 24f, height - 66f)
        panel(c, prev, if (page > 0) th.cyan else th.gray, 2f)
        panel(c, next, if (page < pages - 1) th.cyan else th.gray, 2f)
        center2(c, "< PREV", px(if (page > 0) th.cyan else th.gray, 9f), prev, 4f)
        center2(c, "NEXT >", px(if (page < pages - 1) th.cyan else th.gray, 9f), next, 4f)
        zone(prev, "appctl_prev")
        zone(next, "appctl_next")
        center(c, "0 HOME  -/+ PAGE", px(th.gray, 8f), height - 70f)
    }

    private fun drawGlassAppControllerCoach(c: Canvas) {
        val profile = AppProfiles.get(st.appControllerId) ?: return
        val progress = ((frame - transStart) / 330f).coerceIn(0f, 1f)
        val ease = 1f - (1f - progress) * (1f - progress) * (1f - progress)

        c.save()
        c.translate(-34f * ease, 0f)
        drawHome(c); drawHud(c); drawLegend(c)
        c.restore()
        hitZones.clear()
        pFill.color = Color.argb((64 * ease).toInt(), 40, 28, 18)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)

        val right = width - 24f
        val left = right - 392f + (1f - ease) * 420f
        val drawer = RectF(left, 20f, right, height - 18f)
        drawLiquidGlassPanel(c, drawer, Color.rgb(241, 191, 0), 2.4f)

        val iconDisc = RectF(drawer.left + 24f, drawer.top + 20f, drawer.left + 84f, drawer.top + 80f)
        pFill.color = Color.rgb(255, 252, 245)
        c.drawCircle(iconDisc.centerX(), iconDisc.centerY(), 30f, pFill)
        if (!drawGeneratedIcon(c, profile.id, iconDisc.insetCopy(8f)))
            GlassIcons.draw(c, "more", iconDisc.insetCopy(9f), frame, th.darkIconSurface)
        c.drawText(profile.title, drawer.left + 98f, drawer.top + 44f,
            glassHeadline(Color.rgb(47, 47, 47), 19f))
        c.drawText("PHYSICAL CONTROLS READY", drawer.left + 98f, drawer.top + 66f,
            glassHeadline(Color.rgb(0, 168, 89), 9f))

        val start = st.appControllerPage * 6
        val visible = profile.controls.drop(start).take(6)
        val gap = 10f
        val cardW = (drawer.width() - 50f - gap) / 2f
        visible.forEachIndexed { i, control ->
            val col = i % 2; val row = i / 2
            val x = drawer.left + 25f + col * (cardW + gap)
            val y = drawer.top + 100f + row * 70f
            val r = RectF(x, y, x + cardW, y + 60f)
            drawGlassInsetCard(c, r)
            pFill.color = listOf(th.accent, th.yellow, th.green)[i % 3]
            c.drawCircle(r.left + 22f, r.centerY(), 14f, pFill)
            val key = (i + 1).toString()
            val kp = glassHeadline(Color.WHITE, 9f)
            c.drawText(key, r.left + 22f - kp.measureText(key) / 2f, r.centerY() + 3.5f, kp)
            c.drawText(control.label.take(17), r.left + 45f, r.centerY() + 4f,
                glassHeadline(Color.rgb(54, 54, 54), 9f))
            zone(r, "appctl:${i + 1}")
        }

        val pageCount = maxOf(1, (profile.controls.size + 5) / 6)
        c.drawText("PAGE ${st.appControllerPage + 1}/$pageCount   •   − / + MORE",
            drawer.left + 27f, drawer.bottom - 92f,
            glassHeadline(Color.rgb(104, 93, 79), 8.5f))

        val ok = RectF(drawer.left + 25f, drawer.bottom - 74f, drawer.right - 25f, drawer.bottom - 22f)
        pFill.color = Color.rgb(0, 168, 89)
        c.drawRoundRect(ok, 26f, 26f, pFill)
        center2(c, "OK  —  HIDE CONTROLS", glassHeadline(Color.WHITE, 11f), ok, 4f)
        zone(ok, "appctl_ok")
    }

    private fun drawOneDriveIcon(c: Canvas, r: RectF) {
        val unit = r.width() / 10f
        val blue = Color.rgb(35, 131, 226)
        val light = Color.rgb(81, 169, 245)
        pFill.color = light
        c.drawRect(r.left + unit * 2, r.top + unit * 4, r.left + unit * 6, r.top + unit * 8, pFill)
        c.drawRect(r.left + unit * 3, r.top + unit * 2, r.left + unit * 6, r.top + unit * 8, pFill)
        pFill.color = blue
        c.drawRect(r.left + unit * 5, r.top + unit * 3, r.left + unit * 8, r.top + unit * 8, pFill)
        c.drawRect(r.left + unit, r.top + unit * 6, r.left + unit * 9, r.top + unit * 9, pFill)
    }

    // ---- settings & the rest ------------------------------------------------

    private fun drawSettings(c: Canvas) {
        if (orbitTheme) {
            drawOrbitSettings(c)
            return
        }
        center(c, "* SETTINGS *", px(th.fg, 18f), 82f)
        val rows = listOf(
            Triple("1", "KEY LEARN", "TEACH ME YOUR BUTTONS"),
            Triple("2", "BRIDGE", if (st.connected) "PAIRED: ${st.bridgeName.uppercase()}" else "SEARCHING..."),
            Triple("3", "SOUNDS", if (st.soundAlerts) "CHIPTUNES ON" else "OFF"),
            Triple("4", "THEMES", Themes.current.name),
            Triple("5", "SCREENSAVER", when (st.saverMode) {
                "highway" -> "PIXEL HIGHWAY DEFAULT - OPEN CHOOSER"
                "pet" -> "PETS DEFAULT - OPEN CHOOSER"
                "spider" -> "WEB SLINGER DEFAULT - OPEN CHOOSER"
                else -> "MOUNTAIN DEFAULT - OPEN CHOOSER"
            }),
            Triple("6", "WIFI", "OPEN ANDROID PANEL"),
            Triple("7", "DISPLAY & SOUND", "VOLUME ${st.deviceVolume}%  •  BRIGHTNESS ${st.deviceBrightness}%"),
            Triple("8", "PETS", "CHOOSE COMPANION + CAMERA BEHAVIOR")
        )
        var y = 108f
        for ((num, title, sub) in rows) {
            val r = RectF(20f, y - 22f, width - 20f, y + 10f)
            panel(c, r, thick = 3f)
            c.drawText(num, 34f, y, px(th.yellow, 15f))
            c.drawText(title, 78f, y, px(th.fg, 14f))
            if (sub.isNotEmpty()) c.drawText(sub, 340f, y, px(th.cyan, 11f))
            zone(r, "set:$num")
            y += 42f
        }
    }

    /**
     * Lightweight PSP-style cross-axis settings. Horizontal movement changes
     * the category; vertical movement changes the action within that category.
     * Icons remain free-standing so the supplied artwork is not wrapped in a
     * second decorative bubble.
     */
    private fun drawOrbitSettings(c: Canvas) {
        if (st.settingsCategory != orbitLastSettingsCategory ||
            st.settingsItem != orbitLastSettingsItem) {
            orbitLastSettingsCategory = st.settingsCategory
            orbitLastSettingsItem = st.settingsItem
            orbitSettingsFocusStartedAt = frame
        }

        val categories = listOf(
            Triple("CONNECTION", "stream", intArrayOf(1, 2)),
            Triple("PERSONALIZE", "more", intArrayOf(3, 4, 5)),
            Triple("DEVICE", "tasks", intArrayOf(6, 7, 9)),
            Triple("COMPANION", "chatgpt", intArrayOf(8))
        )
        val rows = mapOf(
            1 to Pair("KEY LEARN", "Teach Manzanilla your physical buttons"),
            2 to Pair("BRIDGE", if (st.connected) "Connected to ${st.bridgeName}" else "Find and pair your PC"),
            3 to Pair("SOUNDS", if (st.soundAlerts) "Portable interface cues on" else "Interface sounds off"),
            4 to Pair("THEMES", Themes.current.name),
            5 to Pair("SCREENSAVER", "Choose the default ambient scene"),
            6 to Pair("WI-FI", "Open Android network settings"),
            7 to Pair("DISPLAY & SOUND", "Volume ${st.deviceVolume}%  •  Brightness ${st.deviceBrightness}%"),
            8 to Pair("PETS", "Companions, camera and behaviour"),
            9 to Pair("MOTION", if (st.orbitSmoothMotion) "Smooth · 30 FPS" else "Eco · 20 FPS")
        )

        c.drawText("SETTINGS", 34f, 84f, glassHeadline(Color.WHITE, 18f))
        c.drawText("CROSS-AXIS CONTROL", 34f, 103f, glassHeadline(th.cyan, 8f))

        val categoryX = floatArrayOf(116f, 324f, 532f, 740f)
        val focusT = if (orbitSettingsFocusStartedAt == 0L) 1f else
            ((frame - orbitSettingsFocusStartedAt) / 320f).coerceIn(0f, 1f)
        val focusScale = (1.0 - exp(-8.0 * focusT) * cos(13.0 * focusT)).toFloat()
        categories.forEachIndexed { index, category ->
            val selected = index == st.settingsCategory
            val iconSize = if (selected) 76f + focusScale * 8f else 50f
            val cx = categoryX[index]
            val cy = if (selected) 151f else 148f
            val icon = RectF(cx - iconSize / 2f, cy - iconSize / 2f,
                cx + iconSize / 2f, cy + iconSize / 2f)
            GlassIcons.draw(c, category.second, icon, frame, true)
            val label = glassHeadline(
                if (selected) Color.WHITE else Color.argb(170, 208, 229, 246),
                if (selected) 10.5f else 8.5f
            )
            c.drawText(category.first, cx - label.measureText(category.first) / 2f, 205f, label)
            if (selected) {
                pFill.color = th.yellow
                c.drawRoundRect(RectF(cx - 34f, 213f, cx + 34f, 217f), 2f, 2f, pFill)
            }
            zone(RectF(cx - 78f, 107f, cx + 78f, 222f), "settings_cat:$index")
        }

        val activeItems = categories[st.settingsCategory].third
        val firstY = 263f
        activeItems.forEachIndexed { index, number ->
            val selected = index == st.settingsItem
            val item = rows.getValue(number)
            val y = firstY + index * 57f
            val xOffset = if (selected) 12f * focusScale else 0f
            val row = RectF(187f, y - 31f, width - 66f, y + 17f)
            pFill.color = if (selected) Color.argb(142, 10, 72, 108)
                else Color.argb(82, 3, 20, 42)
            c.drawRoundRect(row, 20f, 20f, pFill)
            if (selected) {
                c.drawRoundRect(row, 20f, 20f,
                    strokePaint(Color.argb(170, 120, 233, 255), 1.4f))
            }
            val numberPaint = glassHeadline(if (selected) th.yellow else th.cyan, 12f)
            c.drawText(number.toString(), 205f + xOffset, y, numberPaint)
            val titlePaint = glassHeadline(
                if (selected) Color.WHITE else Color.argb(186, 220, 236, 248),
                if (selected) 15.5f else 12f
            )
            c.drawText(item.first, 247f + xOffset, y, titlePaint)
            if (selected) {
                val subPaint = glassHeadline(Color.argb(210, 180, 215, 239), 9.3f)
                c.drawText(fitText(item.second, subPaint, 325f), 436f, y, subPaint)
                c.drawText("›", width - 91f, y + 5f, glassHeadline(th.yellow, 25f))
            }
            zone(row, "set:$number")
        }

        val help = "2/8  UP/DOWN     4/6  LEFT/RIGHT     5  OK     0  BACK"
        val helpPaint = glassHeadline(Color.argb(210, 205, 230, 247), 8.5f)
        val helpBox = RectF(width / 2f - 230f, height - 43f,
            width / 2f + 230f, height - 10f)
        pFill.color = Color.argb(206, 3, 20, 42)
        c.drawRoundRect(helpBox, 16f, 16f, pFill)
        c.drawRoundRect(helpBox, 16f, 16f,
            strokePaint(Color.argb(120, 112, 222, 255), 1f))
        c.drawText(help, width / 2f - helpPaint.measureText(help) / 2f,
            helpBox.centerY() + 3f, helpPaint)
    }

    private fun drawThemes(c: Canvas) {
        if (st.themePreview !in Themes.all.indices) st.themePreview = Themes.idx
        c.drawText("SELECT YOUR", 52f, 88f, glassHeadline(Color.rgb(48, 48, 48), 25f))
        c.drawText("visual world", 242f, 88f, glassEditorial(Color.rgb(170, 21, 27), 28f))
        c.drawText("SPAIN ACADEMY CARD DECK  •  − / + TO MOVE", 55f, 108f,
            glassHeadline(Color.rgb(0, 153, 56), 8f))

        val active = st.themePreview
        val order = listOf(-2, 2, -1, 1, 0)
        for (offset in order) {
            val index = (active + offset + Themes.all.size) % Themes.all.size
            val theme = Themes.all[index]
            val absOff = kotlin.math.abs(offset)
            val cardW = if (offset == 0) 292f else if (absOff == 1) 238f else 202f
            val cardH = if (offset == 0) 250f else if (absOff == 1) 216f else 186f
            val cx = width / 2f + offset * if (absOff == 2) 132f else 160f
            val cy = if (offset == 0) 236f else 242f
            val r = RectF(cx - cardW / 2f, cy - cardH / 2f, cx + cardW / 2f, cy + cardH / 2f)
            val angle = when {
                offset < 0 -> -5.5f + absOff
                offset > 0 -> 5.5f - absOff
                else -> 0f
            }
            c.save()
            c.rotate(angle, r.centerX(), r.centerY())
            pFill.color = when {
                offset == 0 -> Color.rgb(241, 191, 0)
                offset == -1 -> Color.rgb(170, 21, 27)
                offset == 1 -> Color.rgb(48, 48, 48)
                offset == -2 -> Color.rgb(0, 153, 56)
                else -> Color.rgb(255, 253, 247)
            }
            c.drawRoundRect(r, 28f, 28f, pFill)
            c.drawRoundRect(r, 28f, 28f, strokePaint(Color.argb(35, 0, 0, 0), 1.2f))

            val darkText = offset == 0 || offset == 2
            val fg = if (darkText) Color.rgb(42, 35, 0) else Color.WHITE
            val muted = if (darkText) Color.argb(180, 42, 35, 0) else Color.argb(205, 255, 255, 255)
            val pill = RectF(r.left + 24f, r.top + 22f, r.left + 58f, r.top + 56f)
            pFill.color = if (darkText) Color.argb(22, 0, 0, 0) else Color.argb(38, 255, 255, 255)
            c.drawCircle(pill.centerX(), pill.centerY(), 17f, pFill)
            c.drawText((index + 1).toString().padStart(2, '0'), pill.left + 8f, pill.centerY() + 4f,
                glassHeadline(fg, 8.5f))
            c.drawText(fitText(theme.name, glassHeadline(fg, if (offset == 0) 22f else 16f), r.width() - 45f),
                r.left + 24f, r.top + if (offset == 0) 102f else 91f,
                glassHeadline(fg, if (offset == 0) 22f else 16f))
            if (offset == 0) {
                c.drawText(theme.tag, r.left + 25f, r.top + 135f, glassEditorial(muted, 17f))
                c.drawText(if (index == Themes.idx) "CURRENT THEME" else "PRESS APPLY TO USE",
                    r.left + 25f, r.top + 170f, glassHeadline(muted, 8.5f))
                listOf(theme.accent, theme.yellow, theme.green, theme.fg).forEachIndexed { i, color ->
                    pFill.color = color
                    c.drawCircle(r.left + 31f + i * 25f, r.bottom - 39f, 7f, pFill)
                    c.drawCircle(r.left + 31f + i * 25f, r.bottom - 39f, 7f,
                        strokePaint(Color.argb(65, 0, 0, 0), 1f))
                }
                c.drawText("→", r.right - 48f, r.bottom - 29f, glassHeadline(fg, 26f))
            }
            c.restore()
        }

        val prev = RectF(22f, 197f, 78f, 253f)
        val next = RectF(width - 78f, 197f, width - 22f, 253f)
        pFill.color = Color.argb(225, 255, 255, 255)
        c.drawCircle(prev.centerX(), prev.centerY(), 28f, pFill)
        c.drawCircle(next.centerX(), next.centerY(), 28f, pFill)
        c.drawCircle(prev.centerX(), prev.centerY(), 28f, strokePaint(Color.argb(45, 0, 0, 0), 1.2f))
        c.drawCircle(next.centerX(), next.centerY(), 28f, strokePaint(Color.argb(45, 0, 0, 0), 1.2f))
        c.drawText("‹", prev.centerX() - 7f, prev.centerY() + 9f,
            glassHeadline(Color.rgb(48, 48, 48), 28f))
        c.drawText("›", next.centerX() - 6f, next.centerY() + 9f,
            glassHeadline(Color.rgb(48, 48, 48), 28f))
        zone(prev, "theme_prev")
        zone(next, "theme_next")

        val apply = RectF(width / 2f - 96f, 385f, width / 2f + 96f, 430f)
        pFill.color = Color.rgb(0, 168, 89)
        c.drawRoundRect(apply, 23f, 23f, pFill)
        val applyLabel = if (active == Themes.idx) "THEME ACTIVE" else "APPLY THEME"
        center2(c, applyLabel, glassHeadline(Color.WHITE, 10f), apply, 4f)
        zone(apply, "theme_apply")
        val home = RectF(width - 112f, 437f, width - 20f, 469f)
        c.drawText("0  HOME", home.left + 16f, home.centerY() + 4f,
            glassHeadline(Color.rgb(48, 48, 48), 8.5f))
        zone(home, "go_home")
    }

    private fun drawVolume(c: Canvas) {
        if (liquidGlass) {
            drawGlassDisplaySound(c)
            return
        }
        center(c, "* DEVICE VOLUME *", px(th.fg, 18f), 86f)
        val meter = RectF(92f, 126f, width - 92f, 188f)
        panel(c, meter, th.cyan, 4f)
        val inner = RectF(meter.left + 14f, meter.top + 17f,
            meter.right - 14f, meter.bottom - 17f)
        pFill.color = Color.argb(150, 0, 0, 0); c.drawRect(inner, pFill)
        pFill.color = if (st.deviceVolume == 0) th.pink else th.green
        c.drawRect(inner.left, inner.top,
            inner.left + inner.width() * st.deviceVolume / 100f, inner.bottom, pFill)
        center(c, "${st.deviceVolume}%", px(th.fg, 24f), 168f)

        val minus = RectF(90f, 230f, 330f, 360f)
        val mute = RectF(width / 2f - 115f, 230f, width / 2f + 115f, 360f)
        val plus = RectF(width - 330f, 230f, width - 90f, 360f)
        panel(c, minus, th.cyan, 5f); panel(c, mute, th.pink, 5f); panel(c, plus, th.green, 5f)
        center2(c, "-", px(th.cyan, 42f), minus, 23f)
        center2(c, if (st.deviceVolume == 0) "UNMUTE" else "MUTE", px(th.pink, 13f), mute, 31f)
        center2(c, "+", px(th.green, 42f), plus, 23f)
        zone(minus, "volume_down"); zone(mute, "volume_mute"); zone(plus, "volume_up")
        center(c, "4/6 VOLUME   2/8 BRIGHTNESS   5 MUTE", px(th.gray, 10f), 402f)
        center(c, "0 BACK", px(th.cyan, 10f), height - 62f)
    }

    private fun drawGlassDisplaySound(c: Canvas) {
        // An iOS-inspired control centre, but with Spain Academy geometry,
        // warm paper and the same physically refractive Manzanilla material.
        drawHome(c)
        drawHud(c)
        drawLegend(c)
        hitZones.clear()
        pFill.color = Color.argb(72, 45, 28, 12)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)

        val sheet = RectF(168f, 55f, width - 168f, height - 46f)
        drawLiquidGlassPanel(c, sheet, Color.rgb(241, 191, 0), 2.4f)
        c.drawText("DISPLAY & SOUND", sheet.left + 30f, sheet.top + 44f,
            glassHeadline(Color.rgb(48, 48, 48), 20f))
        c.drawText("Drag either liquid control", sheet.left + 31f, sheet.top + 68f,
            glassEditorial(Color.rgb(170, 21, 27), 14f))

        val volumeTrack = RectF(sheet.left + 34f, sheet.top + 118f, sheet.right - 34f, sheet.top + 162f)
        val brightTrack = RectF(sheet.left + 34f, sheet.top + 211f, sheet.right - 34f, sheet.top + 255f)
        drawGlassSlider(c, volumeTrack, st.deviceVolume / 100f, "VOLUME", false)
        drawGlassSlider(c, brightTrack, st.deviceBrightness / 100f, "BRIGHTNESS", true)
        zone(volumeTrack, "slider:volume")
        zone(brightTrack, "slider:brightness")

        val mute = RectF(sheet.left + 34f, sheet.bottom - 68f, sheet.left + 190f, sheet.bottom - 25f)
        val done = RectF(sheet.right - 190f, sheet.bottom - 68f, sheet.right - 34f, sheet.bottom - 25f)
        drawGlassInsetCard(c, mute)
        c.drawText(if (st.deviceVolume == 0) "UNMUTE" else "MUTE", mute.centerX() - 26f,
            mute.centerY() + 4f, glassHeadline(Color.rgb(170, 21, 27), 9.5f))
        pFill.color = Color.rgb(0, 168, 89)
        c.drawRoundRect(done, 22f, 22f, pFill)
        center2(c, "DONE", glassHeadline(Color.WHITE, 10f), done, 4f)
        zone(mute, "volume_mute")
        zone(done, "go_home")
    }

    private fun drawGlassSlider(c: Canvas, r: RectF, fraction: Float, label: String, sun: Boolean) {
        val f = fraction.coerceIn(0f, 1f)
        pFill.color = Color.argb(116, 255, 253, 247)
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, pFill)
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f,
            strokePaint(Color.argb(180, 255, 255, 255), 1.2f))
        val fill = RectF(r.left, r.top, r.left + r.width() * f, r.bottom)
        pFill.color = if (sun) Color.rgb(241, 191, 0) else Color.rgb(0, 168, 89)
        c.drawRoundRect(fill, r.height() / 2f, r.height() / 2f, pFill)
        val knobX = (r.left + r.width() * f).coerceIn(r.left + 22f, r.right - 22f)
        pFill.color = Color.rgb(255, 253, 247)
        pFill.setShadowLayer(8f, 0f, 3f, Color.argb(58, 64, 40, 8))
        setLayerType(LAYER_TYPE_SOFTWARE, pFill)
        c.drawCircle(knobX, r.centerY(), 17f, pFill)
        pFill.clearShadowLayer()
        val glyph = strokePaint(Color.rgb(48, 48, 48), 1.7f)
        if (sun) {
            c.drawCircle(r.left + 21f, r.centerY(), 5f, glyph)
            for (i in 0 until 8) {
                val a = i * Math.PI / 4
                c.drawLine(r.left + 21f + kotlin.math.cos(a).toFloat() * 9f,
                    r.centerY() + kotlin.math.sin(a).toFloat() * 9f,
                    r.left + 21f + kotlin.math.cos(a).toFloat() * 12f,
                    r.centerY() + kotlin.math.sin(a).toFloat() * 12f, glyph)
            }
        } else {
            c.drawRect(r.left + 13f, r.centerY() - 5f, r.left + 19f, r.centerY() + 5f, glyph)
            c.drawLine(r.left + 19f, r.centerY() - 5f, r.left + 26f, r.centerY() - 11f, glyph)
            c.drawLine(r.left + 19f, r.centerY() + 5f, r.left + 26f, r.centerY() + 11f, glyph)
        }
        c.drawText(label, r.left, r.top - 13f, glassHeadline(Color.rgb(48, 48, 48), 9f))
        val value = "${(f * 100).toInt()}%"
        val vp = glassHeadline(Color.rgb(48, 48, 48), 9f)
        c.drawText(value, r.right - vp.measureText(value), r.top - 13f, vp)
    }

    private fun drawPetPicker(c: Canvas) {
        center(c, "* PETS *", px(th.fg, 19f), 78f)
        center(c, "CHOOSE WHO LIVES IN YOUR MANZANILLA", px(th.cyan, 9f), 101f)
        val cards = listOf(
            Triple("MANNI", "FACE + SMILE TRACKING", true),
            Triple("WEBBY", "ROOFTOP CAMERA COMPANION", true),
            Triple("SUHAIR", "AGENT + STATUS COMPANION", true)
        )
        val gap = 16f
        val cw = (width - 64f - gap * 2f) / 3f
        cards.forEachIndexed { i, item ->
            val left = 32f + i * (cw + gap)
            val r = RectF(left, 126f, left + cw, 350f)
            panel(c, r, if (item.third) th.yellow else th.gray, if (item.third) 5f else 3f)
            val preview = RectF(r.left + 16f, r.top + 16f, r.right - 16f, r.top + 132f)
            pFill.color = Color.rgb(6, 15, 32); c.drawRect(preview, pFill)
            if (i == 1) {
                pFill.color = Color.rgb(3, 12, 34); c.drawRect(preview, pFill)
                SpiderIcons.draw(c, "claude", RectF(preview.centerX() - 42f, preview.centerY() - 42f,
                    preview.centerX() + 42f, preview.centerY() + 42f), frame)
            } else if (i == 2) {
                suhairPet.drawPreview(c, preview, frame)
            } else if (item.third) drawPetPreview(c, preview)
            else center2(c, "?", px(th.gray, 28f), preview, 8f)
            labelStrip(c, r, 66f)
            center2(c, "${i + 1}  ${item.first}", px(if (item.third) th.fg else th.gray, 11f), r, 70f)
            center2(c, item.second, px(th.gray, 7f), r, 91f)
            if (i == 0) zone(r, "pet_pick:manni")
            if (i == 1) zone(r, "pet_pick:webby")
            if (i == 2) zone(r, "pet_pick:suhair")
        }
        center(c, "1 MANNI   2 WEBBY   3 SUHAIR   5 OPEN PET   0 BACK", px(th.cyan, 9f), 393f)
    }

    private fun drawPetPreview(c: Canvas, r: RectF) {
        val ew = r.width() * .30f
        val eh = r.height() * .31f
        listOf(r.centerX() - ew * 1.12f, r.centerX() + ew * .12f).forEach { x ->
            val eye = RectF(x, r.centerY() - eh / 2f, x + ew, r.centerY() + eh / 2f)
            pFill.color = Color.rgb(255, 218, 105); c.drawRoundRect(eye, 6f, 6f, pFill)
            pFill.color = Color.rgb(31, 37, 46)
            c.drawRect(eye.centerX() - 6f, eye.centerY() - 8f,
                eye.centerX() + 6f, eye.centerY() + 8f, pFill)
        }
    }

    private fun drawSaverPick(c: Canvas) {
        center(c, "* CHOOSE SCREENSAVER *", px(th.fg, 18f), 84f)
        val gap = 8f
        val cardW = (width - 32f - gap * 3f) / 4f
        val mountain = RectF(16f, 106f, 16f + cardW, 354f)
        val highway = RectF(mountain.right + gap, 106f, mountain.right + gap + cardW, 354f)
        val pets = RectF(highway.right + gap, 106f, highway.right + gap + cardW, 354f)
        val web = RectF(pets.right + gap, 106f, width - 16f, 354f)
        panel(c, mountain, if (st.saverMode == "mountain") th.yellow else th.cyan, 5f)
        panel(c, highway, if (st.saverMode == "highway") th.yellow else th.pink, 5f)
        panel(c, pets, if (st.saverMode == "pet") th.yellow else th.green, 5f)
        panel(c, web, if (st.saverMode == "spider") th.yellow else Color.rgb(225, 42, 54), 5f)

        val mPreview = RectF(mountain.left + 10f, mountain.top + 10f,
            mountain.right - 10f, mountain.bottom - 58f)
        c.drawBitmap(saverLandscape, null, mPreview, spritePaint)
        val hPreview = RectF(highway.left + 10f, highway.top + 10f,
            highway.right - 10f, highway.bottom - 58f)
        pFill.color = Color.rgb(3, 8, 20); c.drawRect(hPreview, pFill)
        saverVideoFrame?.let {
            spritePaint.isFilterBitmap = false
            c.drawBitmap(it, null, hPreview, spritePaint)
        }
        pFill.color = Color.argb(22, 0, 0, 0)
        var y = hPreview.top
        while (y < hPreview.bottom) {
            c.drawRect(hPreview.left, y, hPreview.right, y + 1f, pFill)
            y += 4f
        }
        val pPreview = RectF(pets.left + 10f, pets.top + 10f, pets.right - 10f, pets.bottom - 58f)
        pFill.color = Color.rgb(6, 15, 32); c.drawRect(pPreview, pFill)
        if (st.selectedPet == "suhair") suhairPet.drawPreview(c, pPreview, frame)
        else drawPetPreview(c, pPreview)
        val wPreview = RectF(web.left + 10f, web.top + 10f, web.right - 10f, web.bottom - 58f)
        drawSpiderPreview(c, wPreview)

        labelStrip(c, mountain, 44f); labelStrip(c, highway, 44f); labelStrip(c, pets, 44f); labelStrip(c, web, 44f)
        center2(c, "1 MOUNTAIN", px(th.fg, 10f), mountain, 93f)
        center2(c, "DAY / NIGHT", px(th.gray, 8f), mountain, 112f)
        center2(c, "2 HIGHWAY", px(th.fg, 10f), highway, 93f)
        center2(c, "LOW-RES VIDEO", px(th.gray, 8f), highway, 112f)
        center2(c, "3 PETS", px(th.fg, 10f), pets, 93f)
        center2(c, "CAMERA COMPANION", px(th.gray, 8f), pets, 112f)
        center2(c, "4 WEB SLINGER", px(th.fg, 10f), web, 93f)
        center2(c, "COMIC NIGHT", px(th.gray, 8f), web, 112f)
        zone(mountain, "saver_pick:mountain")
        zone(highway, "saver_pick:highway")
        zone(pets, "saver_pick:pet")
        zone(web, "saver_pick:spider")
        center(c, "SELECTING STARTS IT NOW + SAVES THE DEFAULT", px(th.cyan, 9f), 392f)
        center(c, "0 HOME", px(th.gray, 9f), height - 61f)
    }

    private fun drawSpiderPreview(c: Canvas, r: RectF) {
        c.save()
        c.clipRect(r)
        pFill.color = Color.rgb(3, 12, 34)
        c.drawRect(r, pFill)
        pFill.color = Color.rgb(255, 239, 198)
        c.drawCircle(r.right - 24f, r.top + 24f, 13f, pFill)
        val base = r.bottom
        val bw = r.width() / 7f
        for (i in 0..6) {
            val h = 28f + ((i * 17) % 38)
            pFill.color = if (i % 2 == 0) Color.rgb(10, 35, 72) else Color.rgb(16, 49, 94)
            c.drawRect(r.left + i * bw, base - h, r.left + (i + 1) * bw + 1f, base, pFill)
        }
        SpiderIcons.draw(c, "claude", RectF(r.centerX() - 34f, r.centerY() - 42f,
            r.centerX() + 34f, r.centerY() + 26f), frame)
        c.restore()
    }

    private fun drawPetCalibration(c: Canvas) {
        c.drawColor(Color.rgb(4, 11, 28))
        val preview = RectF(18f, 70f, width * .64f, height - 24f)
        val panel = RectF(width * .66f, 16f, width - 16f, height - 16f)

        pFill.color = Color.rgb(8, 23, 48)
        c.drawRoundRect(preview, 14f, 14f, pFill)
        cameraPreviewFrame?.takeIf { !it.isRecycled }?.let { bitmap ->
            val dstRatio = preview.width() / preview.height()
            val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val src = if (srcRatio > dstRatio) {
                val wanted = (bitmap.height * dstRatio).toInt()
                val left = (bitmap.width - wanted) / 2
                Rect(left, 0, left + wanted, bitmap.height)
            } else {
                val wanted = (bitmap.width / dstRatio).toInt()
                val top = (bitmap.height - wanted) / 2
                Rect(0, top, bitmap.width, top + wanted)
            }
            c.save()
            c.clipPath(Path().apply { addRoundRect(preview, 14f, 14f, Path.Direction.CW) })
            c.drawBitmap(bitmap, src, preview, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            c.restore()
        } ?: run {
            val wait = glassHeadline(Color.rgb(174, 202, 232), 18f)
            val s = if (st.petTrackingAvailable) "STARTING CAMERA…" else "CAMERA UNAVAILABLE"
            c.drawText(s, preview.centerX() - wait.measureText(s) / 2f, preview.centerY(), wait)
        }

        val previewBorder = strokePaint(Color.rgb(79, 172, 235), 3f)
        c.drawRoundRect(preview, 14f, 14f, previewBorder)
        val cx = preview.centerX() - st.petTargetX * preview.width() * .34f
        val cy = preview.centerY() + st.petTargetY * preview.height() * .30f
        val markerColor = if (st.petFacePresent) Color.rgb(92, 236, 152) else Color.rgb(255, 193, 67)
        val targetPaint = strokePaint(markerColor, 3f)
        val faceW = (55f + st.petGestureConfidence * 18f).coerceIn(55f, 75f)
        c.drawOval(RectF(cx - faceW, cy - faceW * .72f, cx + faceW, cy + faceW * .72f), targetPaint)
        c.drawLine(cx - 10f, cy, cx + 10f, cy, targetPaint)
        c.drawLine(cx, cy - 10f, cx, cy + 10f, targetPaint)

        pFill.color = Color.argb(206, 4, 12, 30)
        val live = RectF(preview.left + 10f, preview.bottom - 47f, preview.right - 10f, preview.bottom - 9f)
        c.drawRoundRect(live, 9f, 9f, pFill)
        val liveText = glassHeadline(Color.WHITE, 15f)
        val result = if (st.petGestureCount in 1..3)
            "DETECTED ${st.petGestureCount}  •  RAW ${st.petGestureRaw}  •  ${(st.petGestureConfidence * 100).toInt()}%"
        else if (st.petFacePresent) "FACE LOCKED  •  RAISE YOUR HAND" else "LOOK AT THE CAMERA"
        c.drawText(result, live.left + 13f, live.centerY() + 5f, liveText)

        pFill.color = Color.rgb(247, 242, 219)
        c.drawRoundRect(panel, 18f, 18f, pFill)
        val panelLine = strokePaint(Color.rgb(194, 31, 49), 3f)
        c.drawRoundRect(panel, 18f, 18f, panelLine)
        val title = glassHeadline(Color.rgb(17, 23, 40), 22f)
        c.drawText("OFFLINE CALIBRATION", panel.left + 18f, panel.top + 31f, title)
        val small = glassHeadline(Color.rgb(66, 75, 92), 12f)
        c.drawText("HOLD A POSE, THEN TAP ITS CARD", panel.left + 18f, panel.top + 52f, small)

        for (label in 1..3) {
            val top = panel.top + 70f + (label - 1) * 91f
            val card = RectF(panel.left + 15f, top, panel.right - 15f, top + 76f)
            val saved = st.petCalibrated[label - 1]
            pFill.color = if (saved) Color.rgb(220, 245, 225) else Color.WHITE
            c.drawRoundRect(card, 14f, 14f, pFill)
            c.drawRoundRect(card, 14f, 14f,
                strokePaint(if (saved) Color.rgb(25, 159, 84) else Color.rgb(185, 187, 182), 2f))
            pFill.color = when (label) {
                1 -> Color.rgb(197, 29, 47)
                2 -> Color.rgb(242, 178, 42)
                else -> Color.rgb(25, 143, 91)
            }
            c.drawCircle(card.left + 39f, card.centerY(), 24f, pFill)
            val number = glassHeadline(Color.WHITE, 24f)
            c.drawText(label.toString(), card.left + 39f - number.measureText(label.toString()) / 2f,
                card.centerY() + 8f, number)
            val labelPaint = glassHeadline(Color.rgb(22, 28, 43), 17f)
            c.drawText("$label FINGER${if (label == 1) "" else "S"}", card.left + 75f,
                card.top + 31f, labelPaint)
            val status = glassHeadline(if (saved) Color.rgb(19, 145, 76) else Color.rgb(112, 117, 124), 12f)
            c.drawText(if (saved) "SAVED • TAP TO UPDATE" else "NOT RECORDED", card.left + 75f,
                card.top + 54f, status)
            zone(card, "pet_cal:$label")
        }

        val done = RectF(panel.left + 15f, panel.bottom - 55f, panel.right - 15f, panel.bottom - 13f)
        pFill.color = Color.rgb(190, 27, 46)
        c.drawRoundRect(done, 12f, 12f, pFill)
        val doneText = glassHeadline(Color.WHITE, 16f)
        val doneLabel = "DONE"
        c.drawText(doneLabel, done.centerX() - doneText.measureText(doneLabel) / 2f,
            done.centerY() + 5f, doneText)
        zone(done, "pet_cal_done")

        val back = RectF(15f, 17f, 112f, 57f)
        pFill.color = Color.rgb(8, 23, 48)
        c.drawRoundRect(back, 10f, 10f, pFill)
        val backText = glassHeadline(Color.WHITE, 13f)
        c.drawText("‹ WEBBY", back.left + 13f, back.centerY() + 5f, backText)
        zone(back, "pet_cal_done")
        val secure = glassHeadline(Color.rgb(112, 184, 236), 10f)
        c.drawText("100% ON-DEVICE • CAMERA FRAMES ARE NEVER SAVED OR SENT",
            130f, 43f, secure)
    }

    private fun drawPetGazeCalibration(c: Canvas) {
        c.drawColor(Color.rgb(4, 11, 28))
        val preview = RectF(18f, 70f, width * .64f, height - 24f)
        val panel = RectF(width * .66f, 16f, width - 16f, height - 16f)
        pFill.color = Color.rgb(8, 23, 48)
        c.drawRoundRect(preview, 14f, 14f, pFill)
        cameraPreviewFrame?.takeIf { !it.isRecycled }?.let { bitmap ->
            val dstRatio = preview.width() / preview.height()
            val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val src = if (srcRatio > dstRatio) {
                val wanted = (bitmap.height * dstRatio).toInt()
                val left = (bitmap.width - wanted) / 2
                Rect(left, 0, left + wanted, bitmap.height)
            } else {
                val wanted = (bitmap.width / dstRatio).toInt()
                val top = (bitmap.height - wanted) / 2
                Rect(0, top, bitmap.width, top + wanted)
            }
            c.save()
            c.clipPath(Path().apply { addRoundRect(preview, 14f, 14f, Path.Direction.CW) })
            c.drawBitmap(bitmap, src, preview, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            c.restore()
        }
        c.drawRoundRect(preview, 14f, 14f, strokePaint(Color.rgb(79, 172, 235), 3f))

        val cx = preview.centerX() - st.petTargetX * preview.width() * .34f
        val cy = preview.centerY() + st.petTargetY * preview.height() * .30f
        val lockColor = if (st.petLookingAtDevice) Color.rgb(92, 236, 152)
            else Color.rgb(255, 193, 67)
        val target = strokePaint(lockColor, 3f)
        c.drawOval(RectF(cx - 58f, cy - 43f, cx + 58f, cy + 43f), target)
        c.drawLine(cx - 10f, cy, cx + 10f, cy, target)
        c.drawLine(cx, cy - 10f, cx, cy + 10f, target)

        pFill.color = Color.argb(216, 4, 12, 30)
        val statusBox = RectF(preview.left + 10f, preview.bottom - 51f,
            preview.right - 10f, preview.bottom - 9f)
        c.drawRoundRect(statusBox, 9f, 9f, pFill)
        val status = when {
            !st.petGazeDeviceCalibrated || !st.petGazeLaptopCalibrated -> "SAVE BOTH NORMAL LOOKING POSITIONS"
            st.petLookingAtDevice -> "ATTENTION LOCKED  •  COMMANDS ON"
            st.petFacePresent -> "LOOKING AT LAPTOP  •  COMMANDS OFF"
            else -> "FACE NOT VISIBLE  •  COMMANDS OFF"
        }
        val statusPaint = glassHeadline(lockColor, 15f)
        c.drawText(status, statusBox.left + 13f, statusBox.centerY() + 5f, statusPaint)

        pFill.color = Color.rgb(247, 242, 219)
        c.drawRoundRect(panel, 18f, 18f, pFill)
        c.drawRoundRect(panel, 18f, 18f, strokePaint(Color.rgb(194, 31, 49), 3f))
        val title = glassHeadline(Color.rgb(17, 23, 40), 22f)
        c.drawText("ATTENTION SETUP", panel.left + 18f, panel.top + 31f, title)
        val small = glassHeadline(Color.rgb(66, 75, 92), 11f)
        c.drawText("KEEP YOUR HEAD NATURAL", panel.left + 18f, panel.top + 52f, small)

        fun gazeCard(top: Float, number: String, label: String, detail: String,
                     saved: Boolean, id: String) {
            val card = RectF(panel.left + 15f, top, panel.right - 15f, top + 100f)
            pFill.color = if (saved) Color.rgb(220, 245, 225) else Color.WHITE
            c.drawRoundRect(card, 14f, 14f, pFill)
            c.drawRoundRect(card, 14f, 14f, strokePaint(
                if (saved) Color.rgb(25, 159, 84) else Color.rgb(185, 187, 182), 2f))
            pFill.color = if (number == "1") Color.rgb(194, 31, 49) else Color.rgb(242, 178, 42)
            c.drawCircle(card.left + 36f, card.top + 36f, 22f, pFill)
            val np = glassHeadline(Color.WHITE, 21f)
            c.drawText(number, card.left + 36f - np.measureText(number) / 2f, card.top + 43f, np)
            val lp = glassHeadline(Color.rgb(22, 28, 43), 16f)
            c.drawText(label, card.left + 69f, card.top + 31f, lp)
            val dp = glassHeadline(Color.rgb(82, 88, 99), 11f)
            c.drawText(detail, card.left + 69f, card.top + 51f, dp)
            val sp = glassHeadline(if (saved) Color.rgb(19, 145, 76) else Color.rgb(112, 117, 124), 11f)
            c.drawText(if (saved) "SAVED • TAP TO UPDATE" else "TAP WHILE HOLDING THIS LOOK",
                card.left + 18f, card.bottom - 14f, sp)
            zone(card, id)
        }
        gazeCard(panel.top + 69f, "1", "LOOK AT MANZANILLA", "Eyes toward this camera",
            st.petGazeDeviceCalibrated, "pet_gaze:device")
        gazeCard(panel.top + 181f, "2", "LOOK AT LAPTOP", "Your normal 45° work pose",
            st.petGazeLaptopCalibrated, "pet_gaze:laptop")

        val fingers = RectF(panel.left + 15f, panel.bottom - 99f, panel.right - 15f, panel.bottom - 60f)
        pFill.color = Color.WHITE
        c.drawRoundRect(fingers, 11f, 11f, pFill)
        c.drawRoundRect(fingers, 11f, 11f, strokePaint(Color.rgb(174, 158, 128), 2f))
        val fp = glassHeadline(Color.rgb(45, 50, 61), 12f)
        val fl = "FINGER SETUP"
        c.drawText(fl, fingers.centerX() - fp.measureText(fl) / 2f, fingers.centerY() + 4f, fp)
        zone(fingers, "pet_gaze_fingers")

        val done = RectF(panel.left + 15f, panel.bottom - 52f, panel.right - 15f, panel.bottom - 13f)
        pFill.color = Color.rgb(190, 27, 46)
        c.drawRoundRect(done, 11f, 11f, pFill)
        val donePaint = glassHeadline(Color.WHITE, 15f)
        c.drawText("DONE", done.centerX() - donePaint.measureText("DONE") / 2f,
            done.centerY() + 5f, donePaint)
        zone(done, "pet_gaze_done")

        val back = RectF(15f, 17f, 112f, 57f)
        pFill.color = Color.rgb(8, 23, 48)
        c.drawRoundRect(back, 10f, 10f, pFill)
        val bp = glassHeadline(Color.WHITE, 13f)
        c.drawText("‹ WEBBY", back.left + 13f, back.centerY() + 5f, bp)
        zone(back, "pet_gaze_done")
        val secure = glassHeadline(Color.rgb(112, 184, 236), 10f)
        c.drawText("ON-DEVICE • NO CAMERA FRAMES SAVED OR SENT", 130f, 43f, secure)
    }

    private fun drawSaver(c: Canvas) {
        if (st.saverMode == "spider") {
            webPet.draw(c, width, height, st, frame, { _, _ -> }, false)
            return
        }
        if (st.saverMode == "pet") {
            if (st.selectedPet == "suhair")
                suhairPet.draw(c, width, height, st, frame, ::zone, false)
            else
                petFace.draw(c, width, height, st, frame, { _, _ -> }, false)
            return
        }
        if (st.saverMode == "highway") {
            drawHighwaySaver(c)
            return
        }
        val cal = Calendar.getInstance()
        val hh = "%02d".format(cal.get(Calendar.HOUR_OF_DAY))
        val mm = "%02d".format(cal.get(Calendar.MINUTE))
        val full = RectF(0f, 0f, width.toFloat(), height.toFloat())
        // A very gentle camera float gives the landscape depth without making
        // the grass or tree trunk wobble unnaturally.
        val cameraX = (sin(frame / 12500.0) * 3.2).toFloat()
        val cameraY = (sin(frame / 9300.0 + 1.2) * 2.0).toFloat()
        c.save()
        c.scale(1.018f, 1.018f, width / 2f, height / 2f)
        c.translate(cameraX, cameraY)
        c.drawBitmap(saverLandscape, null, full, spritePaint)

        // Independent moving layers: clouds drift and the lake uses ten
        // hand-painted reflection frames at a calm three frames per second.
        val cloudX = (sin(frame / 19000.0) * 28.0).toFloat()
        val cloudY = (sin(frame / 14000.0 + 0.7) * 2.5).toFloat()
        c.drawBitmap(saverClouds, null,
            RectF(cloudX - 15f, cloudY, width + cloudX + 15f, height + cloudY), spritePaint)
        val reflection = saverReflections[((frame / 330L) % saverReflections.size).toInt()]
        c.drawBitmap(reflection, null, RectF(0f, 326f, width.toFloat(), 454f), spritePaint)
        c.drawBitmap(saverTreeBare, null, full, spritePaint)

        val sw = saverFoliage.width / 2
        val sh = saverFoliage.height / 2
        val foliageDest = listOf(
            RectF(-22f, -20f, 260f, 222f), RectF(126f, -24f, 418f, 222f),
            RectF(-28f, 102f, 272f, 340f), RectF(224f, 96f, 508f, 326f)
        )
        val gust = ((sin(frame / 3600.0) + 1.0) * 0.5).toFloat()
        for (i in 0 until 4) {
            val src = Rect((i % 2) * sw, (i / 2) * sh, (i % 2 + 1) * sw, (i / 2 + 1) * sh)
            val sway = (sin(frame / (1450.0 + i * 150.0) + i * 1.7) * (2.2 + gust * 3.0 + i * 0.25)).toFloat()
            val lift = (sin(frame / (2050.0 + i * 140.0) + i) * (0.9 + gust)).toFloat()
            val d = RectF(foliageDest[i]).apply { offset(sway, lift) }
            val angle = (sin(frame / (2100.0 + i * 170.0) + i * 0.8) * (0.35 + gust * 0.55)).toFloat()
            c.save()
            c.rotate(angle, d.centerX(), d.bottom - 14f)
            c.drawBitmap(saverFoliage, src, d, spritePaint)
            c.restore()
        }

        // Restore the original hand-painted wind sprites. Near leaves are
        // larger/faster while distant leaves remain small, creating real depth.
        val leafSources = listOf(
            Rect(38, 344, 92, 470), Rect(96, 344, 160, 470), Rect(158, 344, 226, 470),
            Rect(218, 344, 294, 470), Rect(286, 344, 360, 470), Rect(352, 344, 430, 470),
            Rect(421, 344, 497, 470), Rect(489, 344, 562, 470), Rect(553, 344, 630, 470),
            Rect(621, 344, 698, 470), Rect(690, 344, 770, 470), Rect(760, 344, 836, 470)
        )
        for (i in leafSources.indices) {
            val cycle = 1550L + (i % 4) * 240L
            val progress = ((frame / (18L + (i % 5) * 3L) + i * 113L) % cycle).toFloat() / cycle
            val depth = when (i % 4) { 0 -> 1.35f; 1 -> 0.78f; 2 -> 1.0f; else -> 0.58f }
            val x = 215f + progress * (760f / depth)
            val baseY = 72f + (i % 6) * 42f
            val y = baseY + progress * (95f + (i % 3) * 28f) +
                (sin(frame / (430.0 + i * 19.0) + i) * (8f + depth * 6f)).toFloat()
            val leafW = 15f * depth
            val leafH = 21f * depth
            val d = RectF(x - leafW / 2f, y - leafH / 2f, x + leafW / 2f, y + leafH / 2f)
            c.save()
            c.rotate((progress * 720f + i * 37f) % 360f, x, y)
            spritePaint.alpha = (150 + 75 * depth.coerceAtMost(1f)).toInt()
            c.drawBitmap(saverFoliageWind, leafSources[i], d, spritePaint)
            spritePaint.alpha = 255
            c.restore()
        }
        c.restore()

        // Real local day/night cycle while preserving the same detailed landscape.
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 19..21, in 5..6 -> { pFill.color = Color.argb(58, 244, 116, 95); c.drawRect(full, pFill) }
            in 22..23, in 0..4 -> {
                pFill.color = Color.argb(142, 4, 18, 55); c.drawRect(full, pFill)
                pFill.color = Color.argb(210, 255, 246, 190)
                for (i in 0 until 18) {
                    val x = ((i * 127 + 330) % width).toFloat()
                    val y = ((i * 43 + 18) % 170).toFloat()
                    c.drawRect(x, y, x + if (i % 5 == 0) 3f else 2f, y + 2f, pFill)
                }
            }
        }

        val clock = RectF(24f, 28f, 318f, 163f)
        pFill.color = Color.argb(195, 7, 24, 53); c.drawRect(clock, pFill)
        pFill.color = Color.rgb(255, 247, 220)
        c.drawRect(clock.left, clock.top, clock.right, clock.top + 3f, pFill)
        c.drawRect(clock.left, clock.bottom - 3f, clock.right, clock.bottom, pFill)
        val timeP = px(Color.rgb(255, 247, 220), 45f)
        c.drawText("$hh${if (blink) ":" else " "}$mm", 42f, 91f, timeP)
        val date = "%ta %te %tb".format(cal, cal, cal).uppercase()
        c.drawText(date, 43f, 121f, px(Color.rgb(246, 202, 83), 11f))
        c.drawText("MANZANILLA  /  JAPAN", 43f, 147f, px(Color.rgb(185, 224, 236), 8f))
        c.drawText("MANZANILLA OS", width - 175f, 31f, px(Color.WHITE, 8f))
        zone(RectF(0f, 0f, width.toFloat(), height.toFloat()), "wake")
    }

    private fun drawSpiderSaver(c: Canvas) {
        drawSpiderBackdrop(c)
        val cal = Calendar.getInstance()
        val time = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val swing = sin(frame / 1900.0).toFloat()
        val anchorX = width * .53f
        val anchorY = -18f
        val heroX = width * .53f + swing * width * .31f
        val heroY = 150f + (1f - cos(frame / 1900.0).toFloat()) * 48f
        val web = strokePaint(Color.argb(205, 218, 235, 255), 2.2f)
        c.drawLine(anchorX, anchorY, heroX, heroY - 25f, web)
        c.save(); c.rotate(swing * 19f, heroX, heroY)
        SpiderIcons.draw(c, "claude", RectF(heroX - 31f, heroY - 31f, heroX + 31f, heroY + 31f), frame)
        c.restore()

        val clockPanel = RectF(28f, 270f, 310f, 430f)
        drawSpiderPanel(c, clockPanel, th.accent, 3f)
        c.drawText(time, 48f, 342f, px(th.fg, 36f))
        val date = "%ta %te %tb".format(cal, cal, cal).uppercase()
        c.drawText(date, 50f, 382f, px(th.cyan, 12f))
        c.drawText("WEB PATROL  /  MANZANILLA", 50f, 410f, px(th.gray, 8f))
        c.drawText("MOVE A KEY TO WAKE", width - 260f, height - 28f, px(th.fg, 9f))
    }

    /** 426x240 video frames enlarged with hard nearest-neighbour pixel edges. */
    private fun drawHighwaySaver(c: Canvas) {
        val cal = Calendar.getInstance()
        val hh = "%02d".format(cal.get(Calendar.HOUR_OF_DAY))
        val mm = "%02d".format(cal.get(Calendar.MINUTE))
        val full = RectF(0f, 0f, width.toFloat(), height.toFloat())
        pFill.color = Color.rgb(3, 8, 20)
        c.drawRect(full, pFill)
        saverVideoFrame?.let { frame ->
            spritePaint.isFilterBitmap = false
            c.drawBitmap(frame, null, full, spritePaint)
        }

        // The same restrained CRT texture used by the OS keeps the live clip
        // visually connected to the lower-resolution pixel interface.
        pFill.color = Color.argb(24, 5, 20, 62)
        c.drawRect(full, pFill)
        pFill.color = Color.argb(26, 0, 0, 0)
        var y = 0f
        while (y < height) {
            c.drawRect(0f, y, width.toFloat(), y + 1f, pFill)
            y += 4f
        }

        val clock = RectF(24f, 28f, 340f, 170f)
        pFill.color = Color.argb(205, 5, 13, 33); c.drawRect(clock, pFill)
        pFill.color = Color.rgb(255, 247, 220)
        c.drawRect(clock.left, clock.top, clock.right, clock.top + 3f, pFill)
        c.drawRect(clock.left, clock.bottom - 3f, clock.right, clock.bottom, pFill)
        c.drawText("$hh${if (blink) ":" else " "}$mm", 42f, 96f,
            px(Color.rgb(255, 247, 220), 48f))
        val date = "%ta %te %tb".format(cal, cal, cal).uppercase()
        c.drawText(date, 43f, 128f, px(Color.rgb(246, 202, 83), 11f))
        c.drawText("MANZANILLA  /  PIXEL HIGHWAY", 43f, 153f,
            px(Color.rgb(185, 224, 236), 8f))
        c.drawText("MANZANILLA OS", width - 175f, 31f, px(Color.WHITE, 8f))
        zone(full, "wake")
    }

    private fun drawDial(c: Canvas) {
        center(c, "* DIAL *", px(th.fg, 18f), 82f)
        val shown = if (st.dialBuf.isEmpty()) "_" else st.dialBuf.takeLast(14)
        center(c, shown, px(th.cyan, 40f), height * 0.40f)
        center(c, "CALL = SEND TO PC CLIPBOARD   # = DELETE", px(th.pink, 11f), height * 0.60f)
        center(c, "LONG-PRESS 0 = HOME", px(th.gray, 10f), height * 0.68f)
    }

    private fun drawStore(c: Canvas) {
        center(c, "* STORE *", px(th.fg, 18f), 82f)
        sprite(c, Sprites.coin, width / 2f - 28f, height * 0.24f, 7f)
        center(c, "PLAY STORE + F-DROID LIVE HERE", px(th.fg, 13f), height * 0.48f)
        val r = RectF(width / 2f - 150, height * 0.55f, width / 2f + 150, height * 0.55f + 56)
        panel(c, r, th.green)
        center(c, "OPEN STORE", px(th.green, 15f), r.centerY() + 6)
        zone(r, "open_store")
        center(c, "0 = HOME", px(th.gray, 10f), height * 0.75f)
    }

    private fun drawKeyLearn(c: Canvas) {
        val fn = Fn.LEARNABLE.getOrNull(learnIndex)
        sprite(c, Sprites.key, width / 2f - 5 * 14f, height * 0.16f, 14f)
        if (fn == null) {
            center(c, "ALL KEYS LEARNED!", px(th.green, 22f), height * 0.60f)
            center(c, "PRESS 0 FOR HOME", px(th.pink, 13f), height * 0.70f)
        } else {
            if (blink) center(c, "PRESS: ${Fn.LABELS[fn]?.uppercase()}", px(th.fg, 17f), height * 0.60f)
            center(c, "STEP ${learnIndex + 1}/${Fn.LEARNABLE.size}  (#=SKIP 0=QUIT)", px(th.gray, 12f), height * 0.72f)
        }
    }

    private fun drawSpawnPick(c: Canvas) {
        center(c, "NEW AGENT - SLOT ${st.selected}", px(th.fg, 18f), height * 0.18f)
        val cw = 250f
        val y = height * 0.26f
        val r1 = RectF(width / 2f - cw - 20, y, width / 2f - 20, y + 220)
        val r2 = RectF(width / 2f + 20, y, width / 2f + cw + 20, y + 220)
        panel(c, r1, th.pink); panel(c, r2, th.accent)
        zone(r1, "pick:claude"); zone(r2, "pick:codex")
        sprite(c, Sprites.flower, r1.centerX() - 45, r1.top + 26, 9f)
        sprite(c, Sprites.book, r2.centerX() - 45, r2.top + 26, 9f)
        center2(c, "1 CLAUDE", px(th.pink, 16f), r1, 92f)
        center2(c, "2 CODEX", px(th.accent, 16f), r2, 92f)
        center(c, "0 = CANCEL", px(th.gray, 12f), height * 0.88f)
    }

    private fun drawCamera(c: Canvas) {
        sprite(c, Sprites.cam, width / 2f - 5 * 13f, height * 0.22f, 13f)
        center(c, "CAMERA", px(th.fg, 22f), height * 0.56f)
        center(c, "# = SNAP TO PC + CURRENT AI", px(th.cyan, 13f), height * 0.66f)
        center(c, "0 = HOME", px(th.gray, 11f), height * 0.74f)
    }

    private fun drawToast(c: Canvas) {
        if (st.toast.isEmpty() || System.currentTimeMillis() > st.toastUntil) return
        val p = px(Color.rgb(16, 20, 40), 14f)
        val w = p.measureText(st.toast.uppercase()) + 36
        val r = RectF((width - w) / 2, height - 96f, (width + w) / 2, height - 62f)
        pFill.color = th.yellow
        c.drawRect(r, pFill)
        c.drawText(st.toast.uppercase(), r.left + 18, r.bottom - 11, p)
    }

    private fun wrap(text: String, p: Paint, widthPx: Int): List<String> {
        val cols = maxOf(16, (widthPx / p.measureText("M")).toInt())
        val out = ArrayList<String>()
        for (raw in text.split('\n')) {
            var s = raw
            while (s.length > cols) { out.add(s.substring(0, cols)); s = s.substring(cols) }
            out.add(s)
        }
        return out
    }

    private fun wrapWords(text: String, p: Paint, widthPx: Int): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            var line = ""
            for (word in paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(candidate) <= widthPx || line.isEmpty()) {
                    line = candidate
                } else {
                    out.add(line)
                    line = word
                }
            }
            if (line.isNotEmpty()) out.add(line) else if (paragraph.isEmpty()) out.add("")
        }
        return out
    }
}

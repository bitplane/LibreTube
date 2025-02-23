package com.github.libretube.fragments

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.text.Html
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.IS_DOWNLOAD_RUNNING
import com.github.libretube.MainActivity
import com.github.libretube.R
import com.github.libretube.adapters.CommentsAdapter
import com.github.libretube.adapters.TrendingAdapter
import com.github.libretube.dialogs.AddtoPlaylistDialog
import com.github.libretube.dialogs.DownloadDialog
import com.github.libretube.dialogs.ShareDialog
import com.github.libretube.formatShort
import com.github.libretube.hideKeyboard
import com.github.libretube.obj.PipedStream
import com.github.libretube.obj.Segment
import com.github.libretube.obj.Segments
import com.github.libretube.obj.Streams
import com.github.libretube.obj.Subscribe
import com.github.libretube.preferences.SponsorBlockSettings
import com.github.libretube.util.CronetHelper
import com.github.libretube.util.RetrofitInstance
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaItem.SubtitleConfiguration
import com.google.android.exoplayer2.MediaItem.fromUri
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.cronet.CronetDataSource
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.RepeatModeUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.squareup.picasso.Picasso
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs
import org.chromium.net.CronetEngine
import retrofit2.HttpException

var isFullScreen = false

class PlayerFragment : Fragment() {

    private val TAG = "PlayerFragment"
    private var videoId: String? = null
    private var sId: Int = 0
    private var eId: Int = 0
    private var paused = false
    private var whichQuality = 0
    private var isZoomed: Boolean = false

    var isSubscribed: Boolean = false

    private lateinit var relatedRecView: RecyclerView
    private lateinit var commentsRecView: RecyclerView
    private var commentsAdapter: CommentsAdapter? = null
    private var commentsLoaded: Boolean? = false
    private var nextPage: String? = null
    private var isLoading = true
    private lateinit var exoPlayerView: StyledPlayerView
    private lateinit var motionLayout: MotionLayout
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var segmentData: Segments

    private lateinit var relDownloadVideo: LinearLayout

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaSessionConnector: MediaSessionConnector
    private lateinit var playerNotification: PlayerNotificationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            videoId = it.getString("videoId")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_player, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hideKeyboard()

        val playerDescription = view.findViewById<TextView>(R.id.player_description)
        videoId = videoId!!.replace("/watch?v=", "")
        relDownloadVideo = view.findViewById(R.id.relPlayer_download)
        val mainActivity = activity as MainActivity
        mainActivity.findViewById<FrameLayout>(R.id.container).visibility = View.VISIBLE
        val playerMotionLayout = view.findViewById<MotionLayout>(R.id.playerMotionLayout)
        motionLayout = playerMotionLayout
        exoPlayerView = view.findViewById(R.id.player)

        view.findViewById<TextView>(R.id.player_description).text = videoId
        playerMotionLayout.addTransitionListener(object : MotionLayout.TransitionListener {
            override fun onTransitionStarted(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int
            ) {
            }

            override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
            ) {
                val mainActivity = activity as MainActivity
                val mainMotionLayout =
                    mainActivity.findViewById<MotionLayout>(R.id.mainMotionLayout)
                mainMotionLayout.progress = abs(progress)
                eId = endId
                sId = startId
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                println(currentId)
                val mainActivity = activity as MainActivity
                val mainMotionLayout =
                    mainActivity.findViewById<MotionLayout>(R.id.mainMotionLayout)
                if (currentId == eId) {
                    view.findViewById<ImageButton>(R.id.exo_play_pause).visibility = View.GONE
                    view.findViewById<ImageButton>(R.id.quality_select).visibility = View.GONE
                    view.findViewById<ImageButton>(R.id.close_imageButton).visibility = View.GONE
                    view.findViewById<TextView>(R.id.quality_text).visibility = View.GONE
                    view.findViewById<ImageButton>(R.id.aspect_ratio_button).visibility = View.GONE
                    mainMotionLayout.progress = 1.toFloat()
                } else if (currentId == sId) {
                    view.findViewById<ImageButton>(R.id.exo_play_pause).visibility = View.VISIBLE
                    view.findViewById<ImageButton>(R.id.quality_select).visibility = View.VISIBLE
                    view.findViewById<ImageButton>(R.id.close_imageButton).visibility = View.VISIBLE
                    view.findViewById<TextView>(R.id.quality_text).visibility = View.VISIBLE
                    view.findViewById<ImageButton>(R.id.aspect_ratio_button)
                        .visibility = View.VISIBLE
                    mainMotionLayout.progress = 0.toFloat()
                }
            }

            override fun onTransitionTrigger(
                motionLayout: MotionLayout?,
                triggerId: Int,
                positive: Boolean,
                progress: Float
            ) {
            }
        })

        playerMotionLayout.progress = 1.toFloat()
        playerMotionLayout.transitionToStart()
        fetchJson(view)
        view.findViewById<ImageView>(R.id.close_imageView).setOnClickListener {
            motionLayout.transitionToEnd()
            val mainActivity = activity as MainActivity
            mainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            mainActivity.supportFragmentManager.beginTransaction()
                .remove(this)
                .commit()
        }
        view.findViewById<ImageButton>(R.id.close_imageButton).setOnClickListener {
            motionLayout.transitionToEnd()
            val mainActivity = activity as MainActivity
            mainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            mainActivity.supportFragmentManager.beginTransaction()
                .remove(this)
                .commit()
        }
        val playImageView = view.findViewById<ImageView>(R.id.play_imageView)
        playImageView.setOnClickListener {
            paused = if (paused) {
                playImageView.setImageResource(R.drawable.ic_pause)
                exoPlayer.play()
                false
            } else {
                playImageView.setImageResource(R.drawable.ic_play)
                exoPlayer.pause()
                true
            }
        }

        view.findViewById<RelativeLayout>(R.id.player_title_layout).setOnClickListener {
            if (playerDescription.isVisible) {
                val image = view.findViewById<ImageView>(R.id.player_description_arrow)
                image.clearAnimation()
                playerDescription.visibility = View.GONE
            } else {
                // toggle button
                val rotate = RotateAnimation(
                    0F,
                    180F,
                    Animation.RELATIVE_TO_SELF,
                    0.5f,
                    Animation.RELATIVE_TO_SELF,
                    0.5f
                )
                rotate.duration = 100
                rotate.interpolator = LinearInterpolator()
                rotate.fillAfter = true
                val image = view.findViewById<ImageView>(R.id.player_description_arrow)
                image.startAnimation(rotate)
                playerDescription.visibility = View.VISIBLE
            }
        }

        view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.comments_toggle)
            .setOnClickListener {
                commentsRecView.visibility =
                    if (commentsRecView.isVisible) View.GONE else View.VISIBLE
                relatedRecView.visibility =
                    if (relatedRecView.isVisible) View.GONE else View.VISIBLE
                if (!commentsLoaded!!) fetchComments()
            }

        // FullScreen button trigger
        view.findViewById<ImageButton>(R.id.fullscreen).setOnClickListener {
            // remember to hide everything when new thing added
            if (!isFullScreen) {
                with(motionLayout) {
                    getConstraintSet(R.id.start).constrainHeight(R.id.player, -1)
                    enableTransition(R.id.yt_transition, false)
                }
                view.findViewById<ConstraintLayout>(R.id.main_container).isClickable = true
                view.findViewById<LinearLayout>(R.id.linLayout).visibility = View.GONE
                val mainActivity = activity as MainActivity
                mainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                isFullScreen = true
            } else {
                with(motionLayout) {
                    getConstraintSet(R.id.start).constrainHeight(R.id.player, 0)
                    enableTransition(R.id.yt_transition, true)
                }
                view.findViewById<ConstraintLayout>(R.id.main_container).isClickable = false
                view.findViewById<LinearLayout>(R.id.linLayout).visibility = View.VISIBLE
                val mainActivity = activity as MainActivity
                mainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                isFullScreen = false
            }
        }

        // switching between original aspect ratio (black bars) and zoomed to fill device screen
        view.findViewById<ImageButton>(R.id.aspect_ratio_button).setOnClickListener {
            if (isZoomed) {
                exoPlayerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                isZoomed = false
            } else {
                exoPlayerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                isZoomed = true
            }
        }

        val scrollView = view.findViewById<ScrollView>(R.id.player_scrollView)
        scrollView.viewTreeObserver
            .addOnScrollChangedListener {
                if (scrollView.getChildAt(0).bottom
                    == (scrollView.height + scrollView.scrollY) &&
                    nextPage != null
                ) {
                    fetchNextComments()
                }
            }

        commentsRecView = view.findViewById(R.id.comments_recView)
        commentsRecView.layoutManager = LinearLayoutManager(view.context)

        commentsRecView.setItemViewCacheSize(20)
        commentsRecView.isDrawingCacheEnabled = true
        commentsRecView.drawingCacheQuality = View.DRAWING_CACHE_QUALITY_HIGH

        relatedRecView = view.findViewById(R.id.player_recView)
        relatedRecView.layoutManager =
            GridLayoutManager(view.context, resources.getInteger(R.integer.grid_items))
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mediaSession.isActive = false
            mediaSession.release()
            mediaSessionConnector.setPlayer(null)
            playerNotification.setPlayer(null)
            val notificationManager = context?.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager
            notificationManager.cancel(1)
            exoPlayer.release()
        } catch (e: Exception) {
        }
    }

    private fun checkForSegments() {
        if (!exoPlayer.isPlaying || !SponsorBlockSettings.sponsorBlockEnabled) return

        exoPlayerView.postDelayed(this::checkForSegments, 100)

        if (!::segmentData.isInitialized || segmentData.segments.isEmpty())
            return

        segmentData.segments.forEach { segment: Segment ->
            val segmentStart = (segment.segment!![0] * 1000.0f).toLong()
            val segmentEnd = (segment.segment[1] * 1000.0f).toLong()
            val currentPosition = exoPlayer.currentPosition
            if (currentPosition in segmentStart until segmentEnd) {
                if (SponsorBlockSettings.sponsorNotificationsEnabled) {
                    Toast.makeText(context, R.string.segment_skipped, Toast.LENGTH_SHORT).show()
                }
                exoPlayer.seekTo(segmentEnd)
            }
        }
    }

    private fun fetchJson(view: View) {
        fun run() {
            lifecycleScope.launchWhenCreated {
                val response = try {
                    RetrofitInstance.api.getStreams(videoId!!)
                } catch (e: IOException) {
                    println(e)
                    Log.e(TAG, "IOException, you might not have internet connection")
                    Toast.makeText(context, R.string.unknown_error, Toast.LENGTH_SHORT).show()
                    return@launchWhenCreated
                } catch (e: HttpException) {
                    Log.e(TAG, "HttpException, unexpected response")
                    Toast.makeText(context, R.string.server_error, Toast.LENGTH_SHORT).show()
                    return@launchWhenCreated
                }
                if (SponsorBlockSettings.sponsorBlockEnabled) {
                    val categories: ArrayList<String> = arrayListOf()
                    if (SponsorBlockSettings.introEnabled) {
                        categories.add("intro")
                    }
                    if (SponsorBlockSettings.selfPromoEnabled) {
                        categories.add("selfpromo")
                    }
                    if (SponsorBlockSettings.interactionEnabled) {
                        categories.add("interaction")
                    }
                    if (SponsorBlockSettings.sponsorsEnabled) {
                        categories.add("sponsor")
                    }
                    if (SponsorBlockSettings.outroEnabled) {
                        categories.add("outro")
                    }
                    if (categories.size > 0) {
                        segmentData = try {

                            RetrofitInstance.api.getSegments(
                                videoId!!,
                                "[\"" + TextUtils.join("\",\"", categories) + "\"]"
                            )
                        } catch (e: IOException) {
                            println(e)
                            Log.e(TAG, "IOException, you might not have internet connection")
                            Toast.makeText(context, R.string.unknown_error, Toast.LENGTH_SHORT)
                                .show()
                            return@launchWhenCreated
                        } catch (e: HttpException) {
                            Log.e(TAG, "HttpException, unexpected response")
                            Toast.makeText(context, R.string.server_error, Toast.LENGTH_SHORT)
                                .show()
                            return@launchWhenCreated
                        }
                    }
                }
                runOnUiThread {
                    createExoPlayer(view)
                    prepareExoPlayerView()
                    setResolutionAndSubtitles(view, response)
                    exoPlayer.prepare()
                    exoPlayer.play()
                    initializePlayerView(view, response)
                }
            }
        }
        run()
    }

    private fun prepareExoPlayerView() {
        exoPlayerView.apply {
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL)
            // controllerShowTimeoutMs = 1500
            controllerHideOnTouch = true
            player = exoPlayer
        }
    }

    private fun initializePlayerView(view: View, response: Streams) {
        view.findViewById<TextView>(R.id.player_views_info).text =
            response.views.formatShort() + " views • " + response.uploadDate
        view.findViewById<TextView>(R.id.textLike).text = response.likes.formatShort()
        val channelImage = view.findViewById<ImageView>(R.id.player_channelImage)
        Picasso.get().load(response.uploaderAvatar).into(channelImage)
        view.findViewById<TextView>(R.id.player_channelName).text = response.uploader

        view.findViewById<TextView>(R.id.title_textView).text = response.title
        view.findViewById<TextView>(R.id.player_title).text = response.title
        view.findViewById<TextView>(R.id.player_description).text = response.description

        // Listener for play and pause icon change
        exoPlayer.addListener(object : com.google.android.exoplayer2.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && SponsorBlockSettings.sponsorBlockEnabled) {
                    exoPlayerView.postDelayed(
                        this@PlayerFragment::checkForSegments,
                        100
                    )
                }
            }

            override fun onPlayerStateChanged(
                playWhenReady: Boolean,
                playbackState: Int
            ) {

                exoPlayerView.keepScreenOn = !(
                    playbackState == Player.STATE_IDLE ||
                        playbackState == Player.STATE_ENDED ||
                        !playWhenReady
                    )

                if (playWhenReady && playbackState == Player.STATE_READY) {
                    // media actually playing
                    view.findViewById<ImageView>(R.id.play_imageView)
                        .setImageResource(R.drawable.ic_pause)
                } else if (playWhenReady) {
                    // might be idle (plays after prepare()),
                    // buffering (plays when data available)
                    // or ended (plays when seek away from end)
                    view.findViewById<ImageView>(R.id.play_imageView)
                        .setImageResource(R.drawable.ic_play)
                } else {
                    // player paused in any state
                    view.findViewById<ImageView>(R.id.play_imageView)
                        .setImageResource(R.drawable.ic_play)
                }
            }
        })

        // share button
        view.findViewById<LinearLayout>(R.id.relPlayer_share).setOnClickListener {
            val shareDialog = ShareDialog(videoId!!)
            shareDialog.show(childFragmentManager, "ShareDialog")
        }
        // check if livestream
        if (response.duration!! > 0) {
            // download clicked
            relDownloadVideo.setOnClickListener {
                if (!IS_DOWNLOAD_RUNNING) {
                    val newFragment = DownloadDialog()
                    var bundle = Bundle()
                    bundle.putString("video_id", videoId)
                    bundle.putParcelable("streams", response)
                    newFragment.arguments = bundle
                    newFragment.show(childFragmentManager, "Download")
                } else {
                    Toast.makeText(context, R.string.dlisinprogress, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        } else {
            Toast.makeText(context, R.string.cannotDownload, Toast.LENGTH_SHORT).show()
        }

        if (response.hls != null) {
            view.findViewById<LinearLayout>(R.id.relPlayer_vlc).setOnClickListener {
                exoPlayer.pause()
                try {
                    val vlcRequestCode = 42
                    val uri: Uri = Uri.parse(response.hls)
                    val vlcIntent = Intent(Intent.ACTION_VIEW)
                    vlcIntent.setPackage("org.videolan.vlc")
                    vlcIntent.setDataAndTypeAndNormalize(uri, "video/*")
                    vlcIntent.putExtra("title", response.title)
                    vlcIntent.putExtra("from_start", false)
                    vlcIntent.putExtra("position", exoPlayer.currentPosition)
                    startActivityForResult(vlcIntent, vlcRequestCode)
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.vlcerror, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        relatedRecView.adapter = TrendingAdapter(
            response.relatedStreams!!,
            childFragmentManager
        )
        val description = response.description!!
        view.findViewById<TextView>(R.id.player_description).text =
            // detect whether the description is html formatted
            if (description.contains("<") && description.contains(">")) {
                if (SDK_INT >= Build.VERSION_CODES.N) {
                    Html.fromHtml(description, Html.FROM_HTML_MODE_COMPACT)
                        .trim()
                } else {
                    Html.fromHtml(description).trim()
                }
            } else {
                description
            }

        view.findViewById<RelativeLayout>(R.id.player_channel).setOnClickListener {

            val activity = view.context as MainActivity
            val bundle = bundleOf("channel_id" to response.uploaderUrl)
            activity.navController.navigate(R.id.channel, bundle)
            activity.findViewById<MotionLayout>(R.id.mainMotionLayout).transitionToEnd()
            view.findViewById<MotionLayout>(R.id.playerMotionLayout).transitionToEnd()
        }
        val sharedPref = context?.getSharedPreferences("token", Context.MODE_PRIVATE)
        if (sharedPref?.getString("token", "") != "") {
            val channelId = response.uploaderUrl?.replace("/channel/", "")
            val subButton = view.findViewById<MaterialButton>(R.id.player_subscribe)
            isSubscribed(subButton, channelId!!)
            view.findViewById<LinearLayout>(R.id.save).setOnClickListener {
                val newFragment = AddtoPlaylistDialog()
                var bundle = Bundle()
                bundle.putString("videoId", videoId)
                newFragment.arguments = bundle
                newFragment.show(childFragmentManager, "AddToPlaylist")
            }
        }
    }

    private fun setResolutionAndSubtitles(view: View, response: Streams) {
        var videosNameArray: Array<CharSequence> = arrayOf()
        videosNameArray += "HLS"
        for (vid in response.videoStreams!!) {
            val name = vid.quality + " " + vid.format
            videosNameArray += name
        }
        var subtitle = mutableListOf<SubtitleConfiguration>()
        if (response.subtitles!!.isNotEmpty()) {
            subtitle.add(
                SubtitleConfiguration.Builder(response.subtitles[0].url!!.toUri())
                    .setMimeType(response.subtitles[0].mimeType!!) // The correct MIME type (required).
                    .setLanguage(response.subtitles[0].code) // The subtitle language (optional).
                    .build()
            )
        }
        val sharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(requireContext())
        val defres = sharedPreferences.getString("default_res", "")!!
        when {
            defres != "" -> {
                run lit@{
                    response.videoStreams.forEachIndexed { index, pipedStream ->
                        if (pipedStream.quality!!.contains(defres)) {
                            val dataSourceFactory: DataSource.Factory =
                                DefaultHttpDataSource.Factory()
                            val videoItem: MediaItem = MediaItem.Builder()
                                .setUri(response.videoStreams[index].url)
                                .setSubtitleConfigurations(subtitle)
                                .build()
                            val videoSource: MediaSource =
                                DefaultMediaSourceFactory(dataSourceFactory)
                                    .createMediaSource(videoItem)
                            var audioSource: MediaSource =
                                DefaultMediaSourceFactory(dataSourceFactory)
                                    .createMediaSource(
                                        fromUri(response.audioStreams!![0].url!!)
                                    )
                            if (response.videoStreams[index].quality == "720p" ||
                                response.videoStreams[index].quality == "1080p" ||
                                response.videoStreams[index].quality == "480p"
                            ) {
                                audioSource =
                                    ProgressiveMediaSource.Factory(dataSourceFactory)
                                        .createMediaSource(
                                            fromUri(
                                                response.audioStreams[
                                                    getMostBitRate(
                                                        response.audioStreams
                                                    )
                                                ].url!!
                                            )
                                        )
                            }
                            val mergeSource: MediaSource =
                                MergingMediaSource(videoSource, audioSource)
                            exoPlayer.setMediaSource(mergeSource)
                            view.findViewById<TextView>(R.id.quality_text).text =
                                videosNameArray[index + 1]
                            return@lit
                        } else if (index + 1 == response.videoStreams.size) {
                            val mediaItem: MediaItem = MediaItem.Builder()
                                .setUri(response.hls)
                                .setSubtitleConfigurations(subtitle)
                                .build()
                            exoPlayer.setMediaItem(mediaItem)
                        }
                    }
                }
            }
            response.hls != null -> {
                val mediaItem: MediaItem = MediaItem.Builder()
                    .setUri(response.hls)
                    .setSubtitleConfigurations(subtitle)
                    .build()
                exoPlayer.setMediaItem(mediaItem)
            }
            else -> {
                val dataSourceFactory: DataSource.Factory =
                    DefaultHttpDataSource.Factory()
                val videoItem: MediaItem = MediaItem.Builder()
                    .setUri(response.videoStreams[0].url)
                    .setSubtitleConfigurations(subtitle)
                    .build()
                val videoSource: MediaSource =
                    DefaultMediaSourceFactory(dataSourceFactory)
                        .createMediaSource(videoItem)
                var audioSource: MediaSource =
                    DefaultMediaSourceFactory(dataSourceFactory)
                        .createMediaSource(fromUri(response.audioStreams!![0].url!!))
                if (response.videoStreams[0].quality == "720p" ||
                    response.videoStreams[0].quality == "1080p" ||
                    response.videoStreams[0].quality == "480p"
                ) {
                    audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(
                            fromUri(
                                response.audioStreams[
                                    getMostBitRate(
                                        response.audioStreams
                                    )
                                ].url!!
                            )
                        )
                }
                val mergeSource: MediaSource =
                    MergingMediaSource(videoSource, audioSource)
                exoPlayer.setMediaSource(mergeSource)
                view.findViewById<TextView>(R.id.quality_text).text = videosNameArray[1]
            }
        }

        view.findViewById<ImageButton>(R.id.quality_select).setOnClickListener {
            // Dialog for quality selection
            val builder: MaterialAlertDialogBuilder? = activity?.let {
                MaterialAlertDialogBuilder(it)
            }
            var lastPosition = exoPlayer.currentPosition
            builder!!.setTitle(R.string.choose_quality_dialog)
                .setItems(
                    videosNameArray,
                    DialogInterface.OnClickListener { _, which ->
                        whichQuality = which
                        if (response.subtitles.isNotEmpty()) {
                            var subtitle =
                                mutableListOf<SubtitleConfiguration>()
                            subtitle.add(
                                SubtitleConfiguration.Builder(
                                    response.subtitles[0].url!!.toUri()
                                )
                                    .setMimeType(response.subtitles[0].mimeType!!) // The correct MIME type (required).
                                    .setLanguage(response.subtitles[0].code) // The subtitle language (optional).
                                    .build()
                            )
                        }
                        if (which == 0) {
                            val mediaItem: MediaItem = MediaItem.Builder()
                                .setUri(response.hls)
                                .setSubtitleConfigurations(subtitle)
                                .build()
                            exoPlayer.setMediaItem(mediaItem)
                        } else {
                            val dataSourceFactory: DataSource.Factory =
                                DefaultHttpDataSource.Factory()
                            val videoItem: MediaItem = MediaItem.Builder()
                                .setUri(response.videoStreams[which - 1].url)
                                .setSubtitleConfigurations(subtitle)
                                .build()
                            val videoSource: MediaSource =
                                DefaultMediaSourceFactory(dataSourceFactory)
                                    .createMediaSource(videoItem)
                            var audioSource: MediaSource =
                                DefaultMediaSourceFactory(dataSourceFactory)
                                    .createMediaSource(
                                        fromUri(response.audioStreams!![0].url!!)
                                    )
                            if (response.videoStreams[which - 1].quality == "720p" ||
                                response.videoStreams[which - 1].quality == "1080p" ||
                                response.videoStreams[which - 1].quality == "480p"
                            ) {
                                audioSource =
                                    ProgressiveMediaSource.Factory(dataSourceFactory)
                                        .createMediaSource(
                                            fromUri(
                                                response.audioStreams[
                                                    getMostBitRate(
                                                        response.audioStreams
                                                    )
                                                ].url!!
                                            )
                                        )
                            }
                            val mergeSource: MediaSource =
                                MergingMediaSource(videoSource, audioSource)
                            exoPlayer.setMediaSource(mergeSource)
                        }
                        exoPlayer.seekTo(lastPosition)
                        view.findViewById<TextView>(R.id.quality_text).text =
                            videosNameArray[which]
                    }
                )
            val dialog = builder.create()
            dialog.show()
        }
    }

    private fun createExoPlayer(view: View) {
        val cronetEngine: CronetEngine = CronetHelper.getCronetEngine()
        val cronetDataSourceFactory: CronetDataSource.Factory =
            CronetDataSource.Factory(cronetEngine, Executors.newCachedThreadPool())

        val dataSourceFactory = DefaultDataSource.Factory(
            requireContext(),
            cronetDataSourceFactory
        )

        // handles the audio focus
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_MOVIE)
            .build()

        // handles the duration of media to retain in the buffer prior to the current playback position (for fast backward seeking)
        val loadControl = DefaultLoadControl.Builder()
            // cache the last three minutes
            .setBackBuffer(1000 * 60 * 3, true)
            .build()

        exoPlayer = ExoPlayer.Builder(view.context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()

        exoPlayer.setAudioAttributes(audioAttributes, true)

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val playbackSpeed = sharedPreferences.getString("playback_speed", "1F")?.toFloat()
        exoPlayer.setPlaybackSpeed(playbackSpeed!!)

        initializePlayerNotification(requireContext())
    }

    private fun initializePlayerNotification(c: Context) {

        mediaSession = MediaSessionCompat(c, this.javaClass.name)
        mediaSession.apply {
            isActive = true
        }

        mediaSessionConnector = MediaSessionConnector(mediaSession)
        mediaSessionConnector.setPlayer(exoPlayer)

        playerNotification = PlayerNotificationManager
            .Builder(c, 1, "background_mode")
            .build()

        playerNotification.apply {
            setPlayer(exoPlayer)
            setUseNextAction(false)
            setUsePreviousAction(false)
            setMediaSessionToken(mediaSession.sessionToken)
        }
    }

    private fun isSubscribed(button: MaterialButton, channel_id: String) {
        @SuppressLint("ResourceAsColor")
        fun run() {
            lifecycleScope.launchWhenCreated {
                val response = try {
                    val sharedPref = context?.getSharedPreferences("token", Context.MODE_PRIVATE)
                    RetrofitInstance.api.isSubscribed(
                        channel_id,
                        sharedPref?.getString("token", "")!!
                    )
                } catch (e: IOException) {
                    println(e)
                    Log.e(TAG, "IOException, you might not have internet connection")
                    return@launchWhenCreated
                } catch (e: HttpException) {
                    Log.e(TAG, "HttpException, unexpected response")
                    return@launchWhenCreated
                }

                runOnUiThread {
                    if (response.subscribed == true) {
                        isSubscribed = true
                        button.text = getString(R.string.unsubscribe)
                    }
                    if (response.subscribed != null) {
                        button.setOnClickListener {
                            if (isSubscribed) {
                                unsubscribe(channel_id)
                                button.text = getString(R.string.subscribe)
                            } else {
                                subscribe(channel_id)
                                button.text = getString(R.string.unsubscribe)
                            }
                        }
                    }
                }
            }
        }
        run()
    }

    private fun subscribe(channel_id: String) {
        fun run() {
            lifecycleScope.launchWhenCreated {
                val response = try {
                    val sharedPref = context?.getSharedPreferences("token", Context.MODE_PRIVATE)
                    RetrofitInstance.api.subscribe(
                        sharedPref?.getString("token", "")!!,
                        Subscribe(channel_id)
                    )
                } catch (e: IOException) {
                    println(e)
                    Log.e(TAG, "IOException, you might not have internet connection")
                    return@launchWhenCreated
                } catch (e: HttpException) {
                    Log.e(TAG, "HttpException, unexpected response$e")
                    return@launchWhenCreated
                }
                isSubscribed = true
            }
        }
        run()
    }

    private fun unsubscribe(channel_id: String) {
        fun run() {
            lifecycleScope.launchWhenCreated {
                val response = try {
                    val sharedPref = context?.getSharedPreferences("token", Context.MODE_PRIVATE)
                    RetrofitInstance.api.unsubscribe(
                        sharedPref?.getString("token", "")!!,
                        Subscribe(channel_id)
                    )
                } catch (e: IOException) {
                    println(e)
                    Log.e(TAG, "IOException, you might not have internet connection")
                    return@launchWhenCreated
                } catch (e: HttpException) {
                    Log.e(TAG, "HttpException, unexpected response")
                    return@launchWhenCreated
                }
                isSubscribed = false
            }
        }
        run()
    }

    private fun Fragment?.runOnUiThread(action: () -> Unit) {
        this ?: return
        if (!isAdded) return // Fragment not attached to an Activity
        activity?.runOnUiThread(action)
    }

    private fun getMostBitRate(audios: List<PipedStream>): Int {
        var bitrate = 0
        var index = 0
        for ((i, audio) in audios.withIndex()) {
            val q = audio.quality!!.replace(" kbps", "").toInt()
            if (q > bitrate) {
                bitrate = q
                index = i
            }
        }
        return index
    }

    private fun fetchComments() {
        lifecycleScope.launchWhenCreated {
            val commentsResponse = try {
                RetrofitInstance.api.getComments(videoId!!)
            } catch (e: IOException) {
                println(e)
                Log.e(TAG, "IOException, you might not have internet connection")
                Toast.makeText(context, R.string.unknown_error, Toast.LENGTH_SHORT).show()
                return@launchWhenCreated
            } catch (e: HttpException) {
                Log.e(TAG, "HttpException, unexpected response")
                Toast.makeText(context, R.string.server_error, Toast.LENGTH_SHORT).show()
                return@launchWhenCreated
            }
            commentsAdapter = CommentsAdapter(videoId!!, commentsResponse.comments)
            commentsRecView.adapter = commentsAdapter
            nextPage = commentsResponse.nextpage
            commentsLoaded = true
            isLoading = false
        }
    }

    private fun fetchNextComments() {
        lifecycleScope.launchWhenCreated {
            if (!isLoading) {
                isLoading = true
                val response = try {
                    RetrofitInstance.api.getCommentsNextPage(videoId!!, nextPage!!)
                } catch (e: IOException) {
                    println(e)
                    Log.e(TAG, "IOException, you might not have internet connection")
                    return@launchWhenCreated
                } catch (e: HttpException) {
                    Log.e(TAG, "HttpException, unexpected response," + e.response())
                    return@launchWhenCreated
                }
                nextPage = response.nextpage
                commentsAdapter?.updateItems(response.comments)
                isLoading = false
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            exoPlayerView.hideController()
            with(motionLayout) {
                getConstraintSet(R.id.start).constrainHeight(R.id.player, -1)
                enableTransition(R.id.yt_transition, false)
            }
            view?.findViewById<ConstraintLayout>(R.id.main_container)?.isClickable = true
            view?.findViewById<LinearLayout>(R.id.linLayout)?.visibility = View.GONE
            view?.findViewById<FrameLayout>(R.id.top_bar)?.visibility = View.GONE
            val mainActivity = activity as MainActivity
            mainActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            isFullScreen = false
        } else {
            with(motionLayout) {
                getConstraintSet(R.id.start).constrainHeight(R.id.player, 0)
                enableTransition(R.id.yt_transition, true)
            }
            view?.findViewById<ConstraintLayout>(R.id.main_container)?.isClickable = false
            view?.findViewById<LinearLayout>(R.id.linLayout)?.visibility = View.VISIBLE
            view?.findViewById<FrameLayout>(R.id.top_bar)?.visibility = View.VISIBLE
        }
    }

    fun onUserLeaveHint() {
        val bounds = Rect()
        val scrollView = view?.findViewById<ScrollView>(R.id.player_scrollView)
        scrollView?.getHitRect(bounds)

        if (SDK_INT >= Build.VERSION_CODES.O &&
            exoPlayer.isPlaying && (
                scrollView?.getLocalVisibleRect(bounds) == true ||
                    isFullScreen
                )
        ) {
            requireActivity().enterPictureInPictureMode()
        }
    }
}

/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2022 Utazukin
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.utazukin.ichaival

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.bumptech.glide.request.target.Target
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

enum class PageCompressFormat {
    JPEG,
    PNG;

    companion object {
        fun PageCompressFormat.toBitmapFormat() : Bitmap.CompressFormat {
            return if (this == JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        }

        fun fromString(format: String?, context: Context?) : PageCompressFormat {
            return when(format) {
                context?.getString(R.string.jpg_compress) -> JPEG
                else -> PNG
            }
        }
    }
}

class ReaderMultiPageFragment : Fragment(), PageFragment {
    private var listener: ReaderFragment.OnFragmentInteractionListener? = null
    private var page = 0
    private var otherPage = 0
    private var imagePath: String? = null
    private var otherImagePath: String? = null
    private var mainImage: View? = null
    private lateinit var pageNum: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var topLayout: RelativeLayout
    private var createViewCalled = false
    private val currentScaleType
        get() = (activity as? ReaderActivity)?.currentScaleType
    private var archiveId: String? = null
    private var rtol: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        val view =  inflater.inflate(R.layout.fragment_reader, container, false)

        arguments?.run {
            page = getInt(PAGE_NUM)
            otherPage = getInt(OTHER_PAGE_ID)
            archiveId = getString(ARCHIVE_ID)
        }

        setHasOptionsMenu(true)

        rtol = if (savedInstanceState == null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            prefs.getBoolean(getString(R.string.rtol_pref_key), false) == !prefs.getBoolean(getString(R.string.dual_page_swap_key), false)
        } else savedInstanceState.getBoolean("rtol")

        topLayout = view.findViewById(R.id.reader_layout)
        pageNum = view.findViewById(R.id.page_num)
        with (pageNum) {
            text = "${page + 1}-${otherPage + 1}"
            visibility = View.VISIBLE
        }

        progressBar = view.findViewById(R.id.progressBar)
        with(progressBar) {
            isIndeterminate = true
            visibility = View.VISIBLE
        }

        //Tapping the view will display the toolbar until the image is displayed.
        with(view) {
            setOnClickListener { listener?.onFragmentTap(TouchZone.Center) }
            setOnLongClickListener { listener?.onFragmentLongPress() == true }
        }

        imagePath?.let { displayImage(it, otherImagePath) }

        createViewCalled = true
        return view
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.swap_merged_page -> {
                rtol = !rtol
                imagePath?.let { displayImage(it, otherImagePath) }
                true
            }
            R.id.split_merged_page -> {
                imagePath?.let { displaySingleImage(it, otherPage, true) }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupImageTapEvents(view: View) {
        when (view) {
            is SubsamplingScaleImageView -> {
                val gestureDetector =
                    GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                            if (view.isReady && e != null)
                                listener?.onFragmentTap(getTouchZone(e.x, view))
                            return true
                        }
                    })

                view.setOnTouchListener { _, e -> gestureDetector.onTouchEvent(e) }
            }
            is PhotoView -> view.setOnViewTapListener { _, x, _ -> listener?.onFragmentTap(getTouchZone(x, view)) }
        }

        view.setOnLongClickListener { listener?.onFragmentLongPress() == true }
    }

    private suspend fun displaySingleImageMain(image: String, failPage: Int) = withContext(Dispatchers.Main) { displaySingleImage(image, failPage) }

    private fun displaySingleImage(image: String, failPage: Int, split: Boolean = false) {
        with(activity as ReaderActivity) { onMergeFailed(page, failPage, split) }
        pageNum.text = (page + 1).toString()
        mainImage = if (image.endsWith(".gif")) {
            PhotoView(activity).also {
                initializeView(it)
                Glide.with(requireActivity())
                    .load(image)
                    .apply(RequestOptions().override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL))
                    .addListener(getListener())
                    .into(ProgressTarget(image, DrawableImageViewTarget(it), progressBar))
            }
        } else {
            SubsamplingScaleImageView(activity).also {
                initializeView(it)

                it.setMaxTileSize(getMaxTextureSize())
                it.setMinimumTileDpi(160)

                Glide.with(requireActivity())
                    .downloadOnly()
                    .load(image)
                    .addListener(getListener(false))
                    .into(
                        ProgressTarget(image, SubsamplingTarget(it, !image.endsWith(".webp")) {
                                pageNum.visibility = View.GONE
                                progressBar.visibility = View.GONE
                                view?.run {
                                    setOnClickListener(null)
                                    setOnLongClickListener(null)
                                }
                                updateScaleType(it, currentScaleType)
                            },
                            progressBar
                        )
                    )
            }
        }.also { setupImageTapEvents(it) }
    }

    private fun createImageView(mergedPath: String, useNewDecoder: Boolean) {
        mainImage = SubsamplingScaleImageView(activity).apply {
            if (useNewDecoder) {
                setBitmapDecoderClass(ImageDecoder::class.java)
                setRegionDecoderClass(ImageRegionDecoder::class.java)
            }
            setOnImageEventListener(object: SubsamplingScaleImageView.OnImageEventListener {
                override fun onReady() {
                    pageNum.visibility = View.GONE
                    progressBar.visibility = View.GONE

                    view?.run {
                        setOnClickListener(null)
                        setOnLongClickListener(null)
                    }
                }
                override fun onImageLoaded() {}
                override fun onPreviewLoadError(e: Exception?) {}
                override fun onImageLoadError(e: Exception?) {}
                override fun onTileLoadError(e: Exception?) {}
                override fun onPreviewReleased() {}
            })
            initializeView(this)
            setMaxTileSize(getMaxTextureSize())
            setMinimumTileDpi(160)
            setImage(ImageSource.uri(mergedPath))
            setupImageTapEvents(this)
        }
    }

    private fun displayImage(image: String, otherImage: String?) {
        imagePath = image
        otherImagePath = otherImage

        if (otherImage == null || image.endsWith(".gif")) {
            displaySingleImage(image, page)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val compressString = prefs.getString(getString(R.string.compression_type_pref), getString(R.string.jpg_compress))
            val compressType = PageCompressFormat.fromString(compressString, requireContext())
            val mergedPath = DualPageHelper.getMergedPage(requireContext().cacheDir, archiveId!!, page, otherPage, rtol, compressType)
            if (mergedPath != null) {
                withContext(Dispatchers.Main) { createImageView(mergedPath, !image.endsWith(".webp")) }
                return@launch
            }

            val target = Glide.with(requireActivity())
                .asBitmap()
                .load(imagePath)
                .submit()
            val otherTarget = Glide.with(requireActivity())
                .asBitmap()
                .load(otherImage)
                .submit()
            progressBar.isIndeterminate = false
            progressBar.progress = 15

            try {
                val dtarget = async { tryOrNull { target.get() } }
                val dotherTarget = async { tryOrNull { otherTarget.get() } }

                val img = dtarget.await()
                if (img == null) {
                    displaySingleImageMain(image, page)
                    return@launch
                }
                progressBar.progress = 45

                val otherImg = dotherTarget.await()
                if (otherImg == null) {
                    displaySingleImageMain(image, otherPage)
                    return@launch
                }
                progressBar.progress = 90

                if (img.width > img.height || otherImg.width > otherImg.height) {
                    val otherImageFail = otherImg.width > otherImg.height
                    displaySingleImageMain(image, if (otherImageFail) otherPage else page)
                } else {
                    //Scale one of the images to match the smaller one if their heights differ too much.
                    val firstImg: Bitmap
                    val secondImg: Bitmap
                    val scaled: Bitmap?
                    when {
                        img.height - otherImg.height < -100 -> {
                            val ar = otherImg.width / otherImg.height.toFloat()
                            val width = ceil(img.height * ar).toInt()
                            scaled = Bitmap.createScaledBitmap(otherImg, width, img.height, true)
                            secondImg = scaled
                            firstImg = img
                        }
                        otherImg.height - img.height < -100 -> {
                            val ar = img.width / img.height.toFloat()
                            val width = ceil(otherImg.height * ar).toInt()
                            scaled = Bitmap.createScaledBitmap(img, width, otherImg.height, true)
                            firstImg = scaled
                            secondImg = otherImg
                        }
                        else -> {
                            firstImg = img
                            secondImg = otherImg
                            scaled = null
                        }
                    }

                    val merged = tryOrNull { mergeBitmaps(firstImg, secondImg, !rtol, requireContext().cacheDir, compressType) }
                    scaled?.recycle()
                    yield()
                    if (merged == null) {
                        progressBar.isIndeterminate = true
                        displaySingleImageMain(image, page)
                    } else {
                        progressBar.progress = 100
                        withContext(Dispatchers.Main) { createImageView(merged, !image.endsWith(".webp")) }
                    }
                }
            } finally {
                target.cancel(false)
                otherTarget.cancel(false)
                activity?.let {
                    Glide.with(it).clear(target)
                    Glide.with(it).clear(otherTarget)
                }
            }
        }
    }

    //Mostly from TachiyomiJ2K
    private suspend fun mergeBitmaps(imageBitmap: Bitmap, imageBitmap2: Bitmap, isLTR: Boolean, cacheDir: File, compressType: PageCompressFormat): String {
        val height = imageBitmap.height
        val width = imageBitmap.width

        val height2 = imageBitmap2.height
        val width2 = imageBitmap2.width
        val maxHeight = max(height, height2)
        yield()
        val pool = Glide.get(requireActivity()).bitmapPool
        val result = pool.get(width + width2, maxHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        val upperPart = Rect(
            if (isLTR) 0 else width2,
            0,
            (if (isLTR) 0 else width2) + imageBitmap.width,
            imageBitmap.height + (maxHeight - imageBitmap.height) / 2
        )
        canvas.drawBitmap(imageBitmap, imageBitmap.rect, upperPart, null)
        progressBar.progress = 95
        val bottomPart = Rect(
            if (!isLTR) 0 else width,
            0,
            (if (!isLTR) 0 else width) + imageBitmap2.width,
            imageBitmap2.height + (maxHeight - imageBitmap2.height) / 2
        )
        canvas.drawBitmap(imageBitmap2, imageBitmap2.rect, bottomPart, null)
        progressBar.progress = 99

        val merged = DualPageHelper.saveMergedPath(cacheDir, result, archiveId!!, page, otherPage, !isLTR, compressType)
        pool.put(result)
        return merged
    }

    private fun initializeView(view: View) {
        view.background = ContextCompat.getDrawable(requireActivity(), android.R.color.black)
        val layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        view.layoutParams = layoutParams
        topLayout.addView(view)
        pageNum.bringToFront()
        progressBar.bringToFront()
    }

    private fun <T> getListener(clearOnReady: Boolean = true) : RequestListener<T> {
        val fragment = this
        return object: RequestListener<T> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<T>?,
                isFirstResource: Boolean
            ): Boolean {
                return listener?.onImageLoadError(fragment) == true
            }

            override fun onResourceReady(
                resource: T?,
                model: Any?,
                target: Target<T>?,
                dataSource: DataSource?,
                isFirstResource: Boolean
            ): Boolean {
                if (clearOnReady) {
                    pageNum.visibility = View.GONE
                    view?.setOnClickListener(null)
                    view?.setOnLongClickListener(null)
                }
                progressBar.visibility = View.GONE
                return false
            }
        }
    }

    override fun reloadImage() {
        imagePath?.let { displayImage(it, otherImagePath) }
    }

    private fun updateScaleType(newScale: ScaleType) = updateScaleType(mainImage, newScale)

    private fun updateScaleType(imageView: View?, scaleType: ScaleType?, useOppositeOrientation: Boolean = false) {
        when (imageView) {
            is SubsamplingScaleImageView -> {
                when (scaleType) {
                    ScaleType.FitPage, null -> {
                        imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                        imageView.resetScaleAndCenter()
                    }
                    ScaleType.FitHeight -> {
                        val vPadding = imageView.paddingBottom - imageView.paddingTop
                        val viewHeight = if (useOppositeOrientation) imageView.width else imageView.height
                        val minScale = (viewHeight - vPadding) / imageView.sHeight.toFloat()
                        imageView.minScale = minScale
                        imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
                        imageView.setScaleAndCenter(minScale, PointF(0f, 0f))
                    }
                    ScaleType.FitWidth -> {
                        val hPadding = imageView.paddingLeft - imageView.paddingRight
                        val viewWidth = if (useOppositeOrientation) imageView.height else imageView.width
                        val minScale = (viewWidth - hPadding) / imageView.sWidth.toFloat()
                        imageView.minScale = minScale
                        imageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
                        imageView.setScaleAndCenter(minScale, PointF(0f, 0f))
                    }
                }
            }
            is PhotoView -> {
                //TODO
            }
        }
    }

    private fun getTouchZone(x: Float, view: View) : TouchZone {
        val location = x / view.width

        if (location <= 0.4)
            return TouchZone.Left

        if (location >= 0.6)
            return TouchZone.Right

        return TouchZone.Center
    }

    override fun onDetach() {
        super.onDetach()
        createViewCalled = false
        (activity as? ReaderActivity)?.unregisterPage(this)
        (mainImage as? SubsamplingScaleImageView)?.recycle()
    }

    override fun onScaleTypeChange(scaleType: ScaleType) = updateScaleType(scaleType)

    override fun onArchiveLoad(archive: Archive) {
        arguments?.run {
            val page = getInt(PAGE_NUM)
            val otherPage = getInt(OTHER_PAGE_ID)
            (activity as CoroutineScope).launch {
                val image = withContext(Dispatchers.IO) { archive.getPageImage(page) }
                val otherImage = withContext(Dispatchers.IO) { archive.getPageImage(otherPage) }
                if (image != null) {
                    if (createViewCalled)
                        displayImage(image, otherImage)
                    else {
                        imagePath = image
                        otherImagePath = otherImage
                    }
                } else
                    listener?.onImageLoadError(this@ReaderMultiPageFragment)
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        (context as ReaderActivity).let {
            listener = it

            it.registerPage(this)
            it.archive?.let { a -> onArchiveLoad(a) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        with(outState) {
            putInt("page", page)
            putString("pagePath", imagePath)
            putBoolean("rtol", rtol)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.run {
            page = getInt("page")
            imagePath = getString("pagePath")
        }
    }

    companion object {
        private const val PAGE_NUM = "page"
        private const val OTHER_PAGE_ID = "other_page"
        private const val ARCHIVE_ID = "id"

        @JvmStatic
        fun createInstance(page: Int, otherPage: Int, archiveId: String?) =
            ReaderMultiPageFragment().apply {
                arguments = Bundle().apply {
                    putInt(PAGE_NUM, page)
                    putInt(OTHER_PAGE_ID, otherPage)
                    putString(ARCHIVE_ID, archiveId)
                }
            }
    }
}
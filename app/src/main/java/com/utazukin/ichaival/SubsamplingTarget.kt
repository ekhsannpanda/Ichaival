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

import android.graphics.drawable.Drawable
import com.bumptech.glide.request.Request
import com.bumptech.glide.request.target.SizeReadyCallback
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.io.File

typealias ResourceListener = () -> Unit

class SubsamplingTarget(private val target: SubsamplingScaleImageView,
                        private val useNewDecoder: Boolean,
                        resourceListener: ResourceListener? = null) : Target<File> {
    private var request: Request? = null
    private val imageEventListener = object: SubsamplingScaleImageView.DefaultOnImageEventListener() {
        override fun onReady() {
            resourceListener?.invoke()
        }
    }

    override fun onLoadStarted(placeholder: Drawable?) { }

    override fun onLoadFailed(errorDrawable: Drawable?) { }

    override fun getSize(cb: SizeReadyCallback) = cb.onSizeReady(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)

    override fun getRequest(): Request? = request

    override fun onStop() { }

    override fun setRequest(request: Request?) {
        this.request = request
    }

    override fun removeCallback(cb: SizeReadyCallback) { }

    override fun onLoadCleared(placeholder: Drawable?) { }

    override fun onResourceReady(resource: File, transition: Transition<in File>?) {
        with(target) {
            if (useNewDecoder) {
                setBitmapDecoderClass(ImageDecoder::class.java)
                setRegionDecoderClass(ImageRegionDecoder::class.java)
            }
            setOnImageEventListener(imageEventListener)
            setImage(ImageSource.uri(resource.absolutePath))
        }
    }

    override fun onStart() { }

    override fun onDestroy() {
        request?.clear()
        target.recycle()
    }
}
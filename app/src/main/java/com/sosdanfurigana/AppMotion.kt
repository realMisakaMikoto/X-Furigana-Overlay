package com.sosdanfurigana

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.ChangeClipBounds
import android.transition.ChangeTransform
import android.transition.Fade
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.window.OnBackInvokedDispatcher
import java.lang.ref.WeakReference

object AppMotion {
    private const val EXTRA_CONTAINER_TRANSITION = "app_motion_container_transition"
    private const val CONTAINER_TRANSITION_NAME = "app_motion_container"
    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)
    private var lastSource = WeakReference<View>(null)

    fun startContainer(activity: Activity, source: View, intent: Intent) {
        if (!animationsEnabled()) {
            activity.startActivity(intent)
            activity.overridePendingTransition(0, 0)
            return
        }
        lastSource.get()?.transitionName = null
        source.transitionName = CONTAINER_TRANSITION_NAME
        lastSource = WeakReference(source)
        intent.putExtra(EXTRA_CONTAINER_TRANSITION, true)
        configureSourceWindow(activity)
        activity.startActivity(
            intent,
            ActivityOptions.makeSceneTransitionAnimation(
                activity,
                source,
                CONTAINER_TRANSITION_NAME
            ).toBundle()
        )
    }

    fun prepareContainerActivity(activity: Activity, savedInstanceState: Bundle?) {
        if (!usesContainerTransition(activity) || !animationsEnabled()) return
        configureTargetWindow(activity)
        if (savedInstanceState == null) activity.postponeEnterTransition()
        installBackHandler(activity)
    }

    fun prepareTabActivity(activity: Activity, savedInstanceState: Bundle?) {
        if (usesContainerTransition(activity)) {
            prepareContainerActivity(activity, savedInstanceState)
        } else if (savedInstanceState != null) {
            activity.overridePendingTransition(0, 0)
        }
    }

    fun bindContainerTarget(activity: Activity, target: View) {
        if (!usesContainerTransition(activity) || !animationsEnabled()) return
        target.transitionName = CONTAINER_TRANSITION_NAME
        if (target is ViewGroup) target.isTransitionGroup = true
        val observer = target.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (target.viewTreeObserver.isAlive) {
                    target.viewTreeObserver.removeOnPreDrawListener(this)
                }
                activity.startPostponedEnterTransition()
                return true
            }
        })
    }

    fun finishContainer(activity: Activity) {
        if (usesContainerTransition(activity) && animationsEnabled()) {
            activity.finishAfterTransition()
            return
        }
        activity.finish()
        if (animationsEnabled()) {
            activity.overridePendingTransition(R.anim.motion_fade_in, R.anim.motion_container_exit)
        } else {
            activity.overridePendingTransition(0, 0)
        }
    }

    fun finishContainerAfterSave(activity: Activity) = finishContainer(activity)

    fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    private fun usesContainerTransition(activity: Activity): Boolean {
        return activity.intent.getBooleanExtra(EXTRA_CONTAINER_TRANSITION, false)
    }

    private fun installBackHandler(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                finishContainer(activity)
            }
        }
    }

    private fun configureSourceWindow(activity: Activity) {
        activity.window.sharedElementExitTransition = containerTransition(220L)
        activity.window.sharedElementReenterTransition = containerTransition(210L)
        activity.window.exitTransition = Fade(Fade.OUT).apply {
            duration = 120L
            interpolator = easing
        }
        activity.window.reenterTransition = Fade(Fade.IN).apply {
            duration = 140L
            interpolator = easing
        }
    }

    private fun configureTargetWindow(activity: Activity) {
        activity.window.sharedElementEnterTransition = containerTransition(260L)
        activity.window.sharedElementReturnTransition = containerTransition(190L)
        activity.window.enterTransition = Fade(Fade.IN).apply {
            duration = 150L
            interpolator = easing
        }
        activity.window.returnTransition = Fade(Fade.OUT).apply {
            duration = 130L
            interpolator = easing
        }
        activity.window.allowEnterTransitionOverlap = true
        activity.window.allowReturnTransitionOverlap = true
        activity.window.sharedElementsUseOverlay = true
    }

    private fun containerTransition(durationMs: Long): TransitionSet {
        return TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(ChangeBounds())
            addTransition(ChangeTransform())
            addTransition(ChangeClipBounds())
            duration = durationMs
            interpolator = easing
        }
    }
}

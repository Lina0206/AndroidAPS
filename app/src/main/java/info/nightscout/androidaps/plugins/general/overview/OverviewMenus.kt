package info.nightscout.androidaps.plugins.general.overview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.ContextMenu
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.FragmentManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import info.nightscout.androidaps.Config
import info.nightscout.androidaps.R
import info.nightscout.androidaps.activities.ErrorHelperActivity
import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.database.entities.TemporaryTarget
import info.nightscout.androidaps.database.transactions.CancelCurrentTemporaryTargetIfAnyTransaction
import info.nightscout.androidaps.database.transactions.InsertTemporaryTargetAndCancelCurrentTransaction
import info.nightscout.androidaps.db.Source
import info.nightscout.androidaps.db.TempTarget
import info.nightscout.androidaps.dialogs.ProfileSwitchDialog
import info.nightscout.androidaps.dialogs.ProfileViewerDialog
import info.nightscout.androidaps.dialogs.TempTargetDialog
import info.nightscout.androidaps.events.EventRefreshOverview
import info.nightscout.androidaps.interfaces.ActivePluginProvider
import info.nightscout.androidaps.interfaces.CommandQueueProvider
import info.nightscout.androidaps.interfaces.PluginType
import info.nightscout.androidaps.interfaces.PumpDescription
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.plugins.aps.loop.LoopPlugin
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunction
import info.nightscout.androidaps.queue.Callback
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.DefaultValueHelper
import info.nightscout.androidaps.utils.ToastUtils
import info.nightscout.androidaps.utils.buildHelper.BuildHelper
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverviewMenus @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val resourceHelper: ResourceHelper,
    private val sp: SP,
    private val rxBus: RxBusWrapper,
    private val context: Context,
    private val buildHelper: BuildHelper,
    private val defaultValueHelper: DefaultValueHelper,
    private val activePlugin: ActivePluginProvider,
    private val profileFunction: ProfileFunction,
    private val commandQueue: CommandQueueProvider,
    private val configBuilderPlugin: ConfigBuilderPlugin,
    private val loopPlugin: LoopPlugin,
    private val repository: AppRepository
) {

    val compositeDisposable = CompositeDisposable()

    enum class CharType(@StringRes val nameId: Int, @ColorRes val colorId: Int, val primary: Boolean, val secondary: Boolean) {
        PRE(R.string.overview_show_predictions, R.color.prediction, primary = true, secondary = false),
        BAS(R.string.overview_show_basals, R.color.basal, primary = true, secondary = false),
        IOB(R.string.overview_show_iob, R.color.iob, primary = false, secondary = true),
        COB(R.string.overview_show_cob, R.color.cob, primary = false, secondary = true),
        DEV(R.string.overview_show_deviations, R.color.deviations, primary = false, secondary = true),
        SEN(R.string.overview_show_sensitivity, R.color.ratio, primary = false, secondary = true),
        ACT(R.string.overview_show_activity, R.color.activity, primary = true, secondary = true),
        DEVSLOPE(R.string.overview_show_deviationslope, R.color.devslopepos, primary = false, secondary = true)
    }

    companion object {
        const val MAX_GRAPHS = 5 // including main
    }
    var setting: MutableList<Array<Boolean>> = ArrayList()

    private fun storeGraphConfig() {
        val sts = Gson().toJson(setting)
        sp.putString(R.string.key_graphconfig, sts)
        aapsLogger.debug(sts)
    }

    private fun loadGraphConfig() {
        val sts = sp.getString(R.string.key_graphconfig, "")
        if (sts.isNotEmpty())
            setting = Gson().fromJson(sts, Array<Array<Boolean>>::class.java).toMutableList()
        else {
            setting = ArrayList()
            setting.add(Array(CharType.values().size) { true })
        }
    }

    fun setupChartMenu(chartButton: ImageButton) {
        loadGraphConfig()
        val numOfGraphs = setting.size // 1 main + x secondary

        chartButton.setOnClickListener { v: View ->
            val predictionsAvailable: Boolean = when {
                Config.APS      -> loopPlugin.lastRun?.request?.hasPredictions ?: false
                Config.NSCLIENT -> true
                else            -> false
            }
            val popup = PopupMenu(v.context, v)

            for (g in 0 until numOfGraphs) {
                if (g != 0 && g < numOfGraphs) {
                    val dividerItem = popup.menu.add(Menu.NONE, g, Menu.NONE, "------- " + "Graph" + " " + g + " -------")
                    dividerItem.isCheckable = true
                    dividerItem.isChecked = true
                }
                CharType.values().forEach { m ->
                    if (g == 0 && !m.primary) return@forEach
                    if (g > 0 && !m.secondary) return@forEach
                    var insert = true
                    if (m == CharType.PRE) insert = predictionsAvailable
                    if (m == CharType.DEVSLOPE) insert = buildHelper.isDev()
                    if (insert) {
                        val item = popup.menu.add(Menu.NONE, m.ordinal + 100 * (g + 1), Menu.NONE, resourceHelper.gs(m.nameId))
                        val title = item.title
                        val s = SpannableString(title)
                        s.setSpan(ForegroundColorSpan(resourceHelper.gc(m.colorId)), 0, s.length, 0)
                        item.title = s
                        item.isCheckable = true
                        item.isChecked = setting[g][m.ordinal]
                    }
                }
            }
            if (numOfGraphs < MAX_GRAPHS) {
                val dividerItem = popup.menu.add(Menu.NONE, numOfGraphs, Menu.NONE, "------- " + "Graph" + " " + numOfGraphs + " -------")
                dividerItem.isCheckable = true
                dividerItem.isChecked = false
            }

            popup.setOnMenuItemClickListener {
                // id < 100 graph header - divider 1, 2, 3 .....
                if (it.itemId == numOfGraphs) {
                    // add new empty
                    setting.add(Array(CharType.values().size) { false })
                } else if (it.itemId < 100) {
                    // remove graph
                    setting.removeAt(it.itemId)
                } else {
                    val graphNumber = it.itemId / 100 - 1
                    val item = it.itemId % 100
                    setting[graphNumber][item] = !it.isChecked
                }
                storeGraphConfig()
                setupChartMenu(chartButton)
                rxBus.send(EventRefreshOverview("OnMenuItemClickListener", now = true))
                return@setOnMenuItemClickListener true
            }
            chartButton.setImageResource(R.drawable.ic_arrow_drop_up_white_24dp)
            popup.setOnDismissListener { chartButton.setImageResource(R.drawable.ic_arrow_drop_down_white_24dp) }
            popup.show()
        }
    }

    fun createContextMenu(menu: ContextMenu, v: View) {
        when (v.id) {
            R.id.overview_apsmode       -> {
                val pumpDescription: PumpDescription = activePlugin.activePump.pumpDescription
                if (!profileFunction.isProfileValid("ContextMenuCreation")) return
                menu.setHeaderTitle(resourceHelper.gs(R.string.loop))
                if (loopPlugin.isEnabled(PluginType.LOOP)) {
                    menu.add(resourceHelper.gs(R.string.disableloop))
                    if (!loopPlugin.isSuspended) {
                        menu.add(resourceHelper.gs(R.string.suspendloopfor1h))
                        menu.add(resourceHelper.gs(R.string.suspendloopfor2h))
                        menu.add(resourceHelper.gs(R.string.suspendloopfor3h))
                        menu.add(resourceHelper.gs(R.string.suspendloopfor10h))
                    } else {
                        if (!loopPlugin.isDisconnected) {
                            menu.add(resourceHelper.gs(R.string.resume))
                        }
                    }
                }
                if (!loopPlugin.isEnabled(PluginType.LOOP)) {
                    menu.add(resourceHelper.gs(R.string.enableloop))
                }
                if (!loopPlugin.isDisconnected) {
                    showSuspendPump(menu, pumpDescription)
                } else {
                    menu.add(resourceHelper.gs(R.string.reconnect))
                }
            }

            R.id.overview_activeprofile -> {
                menu.setHeaderTitle(resourceHelper.gs(R.string.profile))
                menu.add(resourceHelper.gs(R.string.danar_viewprofile))
                if (activePlugin.activeProfileInterface.profile != null) {
                    menu.add(resourceHelper.gs(R.string.careportal_profileswitch))
                }
            }

            R.id.overview_temptarget    -> {
                menu.setHeaderTitle(resourceHelper.gs(R.string.careportal_temporarytarget))
                menu.add(resourceHelper.gs(R.string.custom))
                menu.add(resourceHelper.gs(R.string.eatingsoon))
                menu.add(resourceHelper.gs(R.string.activity))
                menu.add(resourceHelper.gs(R.string.hypo))
                if (activePlugin.activeTreatments.tempTargetFromHistory != null) {
                    menu.add(resourceHelper.gs(R.string.cancel))
                }
            }
        }
    }

    private fun showSuspendPump(menu: ContextMenu, pumpDescription: PumpDescription) {
        if (pumpDescription.tempDurationStep15mAllowed) menu.add(resourceHelper.gs(R.string.disconnectpumpfor15m))
        if (pumpDescription.tempDurationStep30mAllowed) menu.add(resourceHelper.gs(R.string.disconnectpumpfor30m))
        menu.add(resourceHelper.gs(R.string.disconnectpumpfor1h))
        menu.add(resourceHelper.gs(R.string.disconnectpumpfor2h))
        menu.add(resourceHelper.gs(R.string.disconnectpumpfor3h))
    }

    fun onContextItemSelected(item: MenuItem, manager: FragmentManager): Boolean {
        val profile = profileFunction.getProfile() ?: return true
        when (item.title) {
            resourceHelper.gs(R.string.disableloop)                                   -> {
                aapsLogger.debug("USER ENTRY: LOOP DISABLED")
                loopPlugin.setPluginEnabled(PluginType.LOOP, false)
                loopPlugin.setFragmentVisible(PluginType.LOOP, false)
                configBuilderPlugin.storeSettings("DisablingLoop")
                rxBus.send(EventRefreshOverview("suspendmenu"))
                commandQueue.cancelTempBasal(true, object : Callback() {
                    override fun run() {
                        if (!result.success) {
                            ToastUtils.showToastInUiThread(context, resourceHelper.gs(R.string.tempbasaldeliveryerror))
                        }
                    }
                })
                loopPlugin.createOfflineEvent(24 * 60) // upload 24h, we don't know real duration
                return true
            }

            resourceHelper.gs(R.string.enableloop)                                    -> {
                aapsLogger.debug("USER ENTRY: LOOP ENABLED")
                loopPlugin.setPluginEnabled(PluginType.LOOP, true)
                loopPlugin.setFragmentVisible(PluginType.LOOP, true)
                configBuilderPlugin.storeSettings("EnablingLoop")
                rxBus.send(EventRefreshOverview("suspendmenu"))
                loopPlugin.createOfflineEvent(0)
                return true
            }

            resourceHelper.gs(R.string.resume), resourceHelper.gs(R.string.reconnect) -> {
                aapsLogger.debug("USER ENTRY: RESUME")
                loopPlugin.suspendTo(0L)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                commandQueue.cancelTempBasal(true, object : Callback() {
                    override fun run() {
                        if (!result.success) {
                            val i = Intent(context, ErrorHelperActivity::class.java)
                            i.putExtra("soundid", R.raw.boluserror)
                            i.putExtra("status", result.comment)
                            i.putExtra("title", resourceHelper.gs(R.string.tempbasaldeliveryerror))
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(i)
                        }
                    }
                })
                sp.putBoolean(R.string.key_objectiveusereconnect, true)
                loopPlugin.createOfflineEvent(0)
                return true
            }

            resourceHelper.gs(R.string.suspendloopfor1h)                              -> {
                aapsLogger.debug("USER ENTRY: SUSPEND 1h")
                loopPlugin.suspendLoop(60)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            resourceHelper.gs(R.string.suspendloopfor2h)                              -> {
                aapsLogger.debug("USER ENTRY: SUSPEND 2h")
                loopPlugin.suspendLoop(120)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            resourceHelper.gs(R.string.suspendloopfor3h)                              -> {
                aapsLogger.debug("USER ENTRY: SUSPEND 3h")
                loopPlugin.suspendLoop(180)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            resourceHelper.gs(R.string.suspendloopfor10h)                             -> {
                aapsLogger.debug("USER ENTRY: SUSPEND 10h")
                loopPlugin.suspendLoop(600)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            resourceHelper.gs(R.string.disconnectpumpfor15m)                          -> {
                aapsLogger.debug("USER ENTRY: DISCONNECT 15m")
                loopPlugin.disconnectPump(15, profile)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            resourceHelper.gs(R.string.disconnectpumpfor30m)                          -> {
                aapsLogger.debug("USER ENTRY: DISCONNECT 30m")
                loopPlugin.disconnectPump(30, profile)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            resourceHelper.gs(R.string.disconnectpumpfor1h)                           -> {
                aapsLogger.debug("USER ENTRY: DISCONNECT 1h")
                loopPlugin.disconnectPump(60, profile)
                sp.putBoolean(R.string.key_objectiveusedisconnect, true)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            resourceHelper.gs(R.string.disconnectpumpfor2h)                           -> {
                aapsLogger.debug("USER ENTRY: DISCONNECT 2h")
                loopPlugin.disconnectPump(120, profile)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            resourceHelper.gs(R.string.disconnectpumpfor3h)                           -> {
                aapsLogger.debug("USER ENTRY: DISCONNECT 3h")
                loopPlugin.disconnectPump(180, profile)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            resourceHelper.gs(R.string.careportal_profileswitch)                      -> {
                ProfileSwitchDialog().show(manager, "Overview")
            }

            resourceHelper.gs(R.string.danar_viewprofile)                             -> {
                val args = Bundle()
                args.putLong("time", DateUtil.now())
                args.putInt("mode", ProfileViewerDialog.Mode.RUNNING_PROFILE.ordinal)
                val pvd = ProfileViewerDialog()
                pvd.arguments = args
                pvd.show(manager, "ProfileViewDialog")
            }

            resourceHelper.gs(R.string.eatingsoon)                                    -> {
                aapsLogger.debug("USER ENTRY: TEMP TARGET EATING SOON")
                val target = Profile.toMgdl(defaultValueHelper.determineEatingSoonTT(), profileFunction.getUnits())
                compositeDisposable += repository.runTransaction(InsertTemporaryTargetAndCancelCurrentTransaction(
                    timestamp = System.currentTimeMillis(),
                    duration = TimeUnit.MINUTES.toMillis(defaultValueHelper.determineEatingSoonTTDuration().toLong()),
                    reason = TemporaryTarget.Reason.EATING_SOON,
                    lowTarget = target,
                    highTarget = target
                )).subscribe()
            }

            resourceHelper.gs(R.string.activity)                                      -> {
                aapsLogger.debug("USER ENTRY: TEMP TARGET ACTIVITY")
                val target = Profile.toMgdl(defaultValueHelper.determineActivityTT(), profileFunction.getUnits())
                compositeDisposable += repository.runTransaction(InsertTemporaryTargetAndCancelCurrentTransaction(
                    timestamp = System.currentTimeMillis(),
                    duration = TimeUnit.MINUTES.toMillis(defaultValueHelper.determineActivityTTDuration().toLong()),
                    reason = TemporaryTarget.Reason.ACTIVITY,
                    lowTarget = target,
                    highTarget = target
                )).subscribe()
            }

            resourceHelper.gs(R.string.hypo)                                          -> {
                aapsLogger.debug("USER ENTRY: TEMP TARGET HYPO")
                val target = Profile.toMgdl(defaultValueHelper.determineHypoTT(), profileFunction.getUnits())
                compositeDisposable += repository.runTransaction(InsertTemporaryTargetAndCancelCurrentTransaction(
                    timestamp = System.currentTimeMillis(),
                    duration = TimeUnit.MINUTES.toMillis(defaultValueHelper.determineHypoTTDuration().toLong()),
                    reason = TemporaryTarget.Reason.HYPOGLYCEMIA,
                    lowTarget = target,
                    highTarget = target
                )).subscribe()
            }

            resourceHelper.gs(R.string.custom)                                        -> {
                TempTargetDialog().show(manager, "Overview")
            }

            resourceHelper.gs(R.string.cancel)                                        -> {
                aapsLogger.debug("USER ENTRY: TEMP TARGET CANCEL")
                compositeDisposable += repository.runTransaction(CancelCurrentTemporaryTargetIfAnyTransaction(System.currentTimeMillis())).subscribe()
            }
        }
        return false
    }

}
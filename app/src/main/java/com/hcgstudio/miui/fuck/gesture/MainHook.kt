package com.hcgstudio.miui.fuck.gesture

import android.content.Intent
import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.xposed.proxy.YukiHookXposedInitProxy
import de.robv.android.xposed.XposedHelpers


@InjectYukiHookWithXposed(modulePackageName = "com.hcgstudio.miui.fuck.gesture")
class MainHook : YukiHookXposedInitProxy {
    override fun onHook() {
        YukiHookAPI.encase {

            loadApp(name = "com.android.systemui") {
                findClass("com.android.systemui.recents.MiuiFullScreenGestureProxy").hook {
                    injectMember {
                        method {
                            name = "updateDefaultHome"
                        }
                        replaceTo({})
                    }
                }

                val strongMode = prefs.get(DataConst.STRONG_MODE_DATA)

                // Strong mode hook - force MiuiSettings.Global.getBoolean("force_fsg_nav_bar") = true
                if (strongMode) {
                    findClass("android.provider.MiuiSettings\$Global").hook {
                        injectMember {
                            method {
                                name = "getBoolean"
                                returnType = BooleanType
                            }
                            afterHook {
                                if (args(1).string() == "force_fsg_nav_bar") {
                                    resultTrue()
                                }
                            }
                        }
                    }
                }

                // Hook areNavigationButtonForcedVisible() to return false
                // This prevents MIUI from setting mIsBackGestureAllowed = false
                findClass("com.android.internal.policy.GestureNavigationSettingsObserver").hook {
                    injectMember {
                        method {
                            name = "areNavigationButtonForcedVisible"
                            returnType = BooleanType
                        }
                        replaceToFalse()
                    }
                }

                // Fix timing issue: hook updateCurrentUserResources() to force mIsBackGestureAllowed = true
                // Because the hook above may be applied AFTER this method is called during SystemUI startup
                findClass("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler").hook {
                    injectMember {
                        method {
                            name = "updateCurrentUserResources"
                        }
                        afterHook {
                            // Force mIsBackGestureAllowed = true after method execution
                            XposedHelpers.setBooleanField(instance, "mIsBackGestureAllowed", true)
                        }
                    }
                }

                // CRITICAL FIX: Register "edge-swipe" InputMonitor for third-party launchers.
                // updateIsEnabled() dead path → mIsEnabled never true → return early →
                // InputMonitor never registered → no side gesture events.
                // Fix: afterHook on updateIsEnabled(), if mInputMonitor is null,
                // manually execute the :cond_55 enable logic.
                findClass("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler").hook {
                    injectMember {
                        method {
                            name = "updateIsEnabled"
                        }
                        afterHook {
                            val handler = instance
                            val inputMonitor = XposedHelpers.getObjectField(handler, "mInputMonitor")
                            if (inputMonitor == null) {
                                // :cond_55 - Register GestureNavigationSettingsObserver
                                val observer = XposedHelpers.getObjectField(handler, "mGestureNavigationSettingsObserver")
                                XposedHelpers.callMethod(observer, "register")

                                // :line 495 - Update display size
                                XposedHelpers.callMethod(handler, "updateDisplaySize")

                                // :line 511 - Create InputMonitor via InputManager.monitorGestureInput("edge-swipe", displayId)
                                val inputManagerClass = findClass("android.hardware.input.InputManager")
                                val inputManager = XposedHelpers.callStaticMethod(inputManagerClass, "getInstance")
                                val displayId = XposedHelpers.getIntField(handler, "mDisplayId")
                                val newInputMonitor = XposedHelpers.callMethod(inputManager, "monitorGestureInput", "edge-swipe", displayId)
                                XposedHelpers.setObjectField(handler, "mInputMonitor", newInputMonitor)

                                // Create NavigationBarEdgePanel and set as EdgeBackPlugin
                                val context = XposedHelpers.getObjectField(handler, "mContext")
                                val backAnimation = XposedHelpers.getObjectField(handler, "mBackAnimation")
                                val latencyTracker = XposedHelpers.getObjectField(handler, "mLatencyTracker")
                                val edgePanelClass = findClass("com.android.systemui.navigationbar.gestural.NavigationBarEdgePanel")
                                val edgePanel = edgePanelClass.getConstructor(
                                    findClass("android.content.Context"),
                                    findClass("com.android.wm.shell.back.BackAnimation"),
                                    findClass("com.android.internal.util.LatencyTracker")
                                ).newInstance(context, backAnimation, latencyTracker)
                                XposedHelpers.callMethod(handler, "setEdgeBackPlugin", edgePanel)

                                // Set mIsEnabled = true
                                XposedHelpers.setBooleanField(handler, "mIsEnabled", true)
                            }
                        }
                    }
                }
            }

            loadApp(name = "com.miui.home") {
                findClass("androidx.preference.Preference").hook {
                    injectMember {
                        method {
                            name = "setIntent"
                            beforeHook {
                                val old = args(0).any() as Intent
                                if (old.action == "com.miui.home.action.navigation_bar_type_settings") {
                                    val intent = Intent()
                                    intent.setClassName(
                                        "com.hcgstudio.miui.fuck.gesture",
                                        "com.hcgstudio.miui.fuck.gesture.SettingsActivity"
                                    )
                                    args(0).set(intent)
                                }
                            }
                        }
                    }
                }

                findClass("com.miui.home.launcher.common.Utilities").hook {
                    injectMember {
                        method {
                            name = "isUseMiuiHomeAsDefaultHome"
                            returnType = BooleanType
                        }
                        replaceToTrue()
                    }
                }
            }

            loadApp(name = "com.miui.voiceassist") {
                findClass("android.provider.Settings\$Global").hook {
                    injectMember {
                        method {
                            name = "putInt"
                        }
                        beforeHook {
                            if (args(1).string() == "force_fsg_nav_bar") {
                                args(2).set(1)
                            }
                        }
                    }
                }
            }
        }
    }

}
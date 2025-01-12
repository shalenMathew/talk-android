/*
 * Nextcloud Talk application
 *
 * @author Marcel Hibbe
 * Copyright (C) 2022 Marcel Hibbe <dev@mhibbe.de>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextcloud.talk.ui.dialog

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import autodagger.AutoInjector
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nextcloud.talk.R
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.conversation.RenameConversationDialogFragment
import com.nextcloud.talk.conversationlist.ConversationsListActivity
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.databinding.DialogConversationOperationsBinding
import com.nextcloud.talk.jobs.LeaveConversationWorker
import com.nextcloud.talk.models.json.conversations.Conversation
import com.nextcloud.talk.models.json.generic.GenericOverall
import com.nextcloud.talk.ui.theme.ViewThemeUtils
import com.nextcloud.talk.users.UserManager
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_INTERNAL_USER_ID
import com.nextcloud.talk.utils.bundle.BundleKeys.KEY_ROOM_TOKEN
import com.nextcloud.talk.utils.database.user.CapabilitiesUtilNew
import io.reactivex.Observer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class ConversationsListBottomDialog(
    val activity: ConversationsListActivity,
    val currentUser: User,
    val conversation: Conversation
) : BottomSheetDialog(activity) {

    private lateinit var binding: DialogConversationOperationsBinding

    @Inject
    lateinit var ncApi: NcApi

    @Inject
    lateinit var viewThemeUtils: ViewThemeUtils

    @Inject
    lateinit var userManager: UserManager

    lateinit var credentials: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NextcloudTalkApplication.sharedApplication?.componentApplication?.inject(this)

        binding = DialogConversationOperationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        viewThemeUtils.platform.themeDialog(binding.root)
        initHeaderDescription()
        initItemsVisibility()
        initClickListeners()

        credentials = ApiUtils.getCredentials(currentUser.username, currentUser.token)
    }

    override fun onStart() {
        super.onStart()
        val bottomSheet = findViewById<View>(R.id.design_bottom_sheet)
        val behavior = BottomSheetBehavior.from(bottomSheet as View)
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun initHeaderDescription() {
        if (!TextUtils.isEmpty(conversation.displayName)) {
            binding.conversationOperationHeader.text = conversation.displayName
        } else if (!TextUtils.isEmpty(conversation.name)) {
            binding.conversationOperationHeader.text = conversation.name
        }
    }

    private fun initItemsVisibility() {
        val hasFavoritesCapability = CapabilitiesUtilNew.hasSpreedFeatureCapability(currentUser, "favorites")
        val canModerate = conversation.canModerate(currentUser)

        binding.conversationRemoveFromFavorites.visibility = setVisibleIf(
            hasFavoritesCapability && conversation.favorite
        )
        binding.conversationAddToFavorites.visibility = setVisibleIf(
            hasFavoritesCapability && !conversation.favorite
        )

        binding.conversationMarkAsRead.visibility = setVisibleIf(
            conversation.unreadMessages > 0 && CapabilitiesUtilNew.canSetChatReadMarker(currentUser)
        )

        binding.conversationMarkAsUnread.visibility = setVisibleIf(
            conversation.unreadMessages <= 0 && CapabilitiesUtilNew.canMarkRoomAsUnread(currentUser)
        )

        binding.conversationOperationRename.visibility = setVisibleIf(
            conversation.isNameEditable(currentUser)
        )

        binding.conversationOperationDelete.visibility = setVisibleIf(
            canModerate
        )

        binding.conversationOperationLeave.visibility = setVisibleIf(
            conversation.canLeave() &&
                // leaving is by api not possible for the last user with moderator permissions.
                // for now, hide this option for all moderators.
                !conversation.canModerate(currentUser)
        )
    }

    private fun setVisibleIf(boolean: Boolean): Int {
        return if (boolean) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun initClickListeners() {
        binding.conversationAddToFavorites.setOnClickListener {
            addConversationToFavorites()
        }

        binding.conversationRemoveFromFavorites.setOnClickListener {
            removeConversationFromFavorites()
        }

        binding.conversationMarkAsRead.setOnClickListener {
            markConversationAsRead()
        }

        binding.conversationMarkAsUnread.setOnClickListener {
            markConversationAsUnread()
        }

        binding.conversationOperationRename.setOnClickListener {
            renameConversation()
        }

        binding.conversationOperationLeave.setOnClickListener {
            leaveConversation()
        }

        binding.conversationOperationDelete.setOnClickListener {
            deleteConversation()
        }
    }

    private fun addConversationToFavorites() {
        val apiVersion = ApiUtils.getConversationApiVersion(currentUser, intArrayOf(ApiUtils.APIv4, ApiUtils.APIv1))
        ncApi.addConversationToFavorites(
            credentials,
            ApiUtils.getUrlForRoomFavorite(
                apiVersion,
                currentUser.baseUrl,
                conversation.token
            )
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .retry(1)
            .subscribe(object : Observer<GenericOverall> {
                override fun onSubscribe(d: Disposable) {
                    // unused atm
                }

                override fun onNext(genericOverall: GenericOverall) {
                    activity.fetchRooms()
                    activity.showSnackbar(
                        String.format(
                            context.resources.getString(R.string.added_to_favorites),
                            conversation.displayName
                        )
                    )
                    dismiss()
                }

                override fun onError(e: Throwable) {
                    activity.showSnackbar(context.resources.getString(R.string.nc_common_error_sorry))
                    dismiss()
                }

                override fun onComplete() {
                    // unused atm
                }
            })
    }

    private fun removeConversationFromFavorites() {
        val apiVersion = ApiUtils.getConversationApiVersion(currentUser, intArrayOf(ApiUtils.APIv4, ApiUtils.APIv1))
        ncApi.removeConversationFromFavorites(
            credentials,
            ApiUtils.getUrlForRoomFavorite(
                apiVersion,
                currentUser.baseUrl,
                conversation.token
            )
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .retry(1)
            .subscribe(object : Observer<GenericOverall> {
                override fun onSubscribe(d: Disposable) {
                    // unused atm
                }

                override fun onNext(genericOverall: GenericOverall) {
                    activity.fetchRooms()
                    activity.showSnackbar(
                        String.format(
                            context.resources.getString(R.string.removed_from_favorites),
                            conversation.displayName
                        )
                    )
                    dismiss()
                }

                override fun onError(e: Throwable) {
                    activity.showSnackbar(context.resources.getString(R.string.nc_common_error_sorry))
                    dismiss()
                }

                override fun onComplete() {
                    // unused atm
                }
            })
    }

    private fun markConversationAsUnread() {
        ncApi.markRoomAsUnread(
            credentials,
            ApiUtils.getUrlForChatReadMarker(
                chatApiVersion(),
                currentUser.baseUrl,
                conversation.token
            )
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .retry(1)
            .subscribe(object : Observer<GenericOverall> {
                override fun onSubscribe(d: Disposable) {
                    // unused atm
                }

                override fun onNext(genericOverall: GenericOverall) {
                    activity.fetchRooms()
                    activity.showSnackbar(
                        String.format(
                            context.resources.getString(R.string.marked_as_unread),
                            conversation.displayName
                        )
                    )
                    dismiss()
                }

                override fun onError(e: Throwable) {
                    activity.showSnackbar(context.resources.getString(R.string.nc_common_error_sorry))
                    dismiss()
                }

                override fun onComplete() {
                    // unused atm
                }
            })
    }

    private fun markConversationAsRead() {
        ncApi.setChatReadMarker(
            credentials,
            ApiUtils.getUrlForChatReadMarker(
                chatApiVersion(),
                currentUser.baseUrl,
                conversation.token
            ),
            conversation.lastMessage!!.jsonMessageId
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .retry(1)
            .subscribe(object : Observer<GenericOverall> {
                override fun onSubscribe(d: Disposable) {
                    // unused atm
                }

                override fun onNext(genericOverall: GenericOverall) {
                    activity.fetchRooms()
                    activity.showSnackbar(
                        String.format(
                            context.resources.getString(R.string.marked_as_read),
                            conversation.displayName
                        )
                    )
                    dismiss()
                }

                override fun onError(e: Throwable) {
                    activity.showSnackbar(context.resources.getString(R.string.nc_common_error_sorry))
                    dismiss()
                }

                override fun onComplete() {
                    // unused atm
                }
            })
    }

    private fun renameConversation() {
        if (!TextUtils.isEmpty(conversation.token)) {
            dismiss()
            val conversationDialog = RenameConversationDialogFragment.newInstance(
                conversation.token!!,
                conversation.displayName!!
            )
            conversationDialog.show(
                activity.supportFragmentManager,
                TAG
            )
        }
    }
    private fun leaveConversation() {
        val dataBuilder = Data.Builder()
        dataBuilder.putString(KEY_ROOM_TOKEN, conversation.token)
        dataBuilder.putLong(KEY_INTERNAL_USER_ID, currentUser.id!!)
        val data = dataBuilder.build()

        val leaveConversationWorker =
            OneTimeWorkRequest.Builder(LeaveConversationWorker::class.java).setInputData(
                data
            ).build()
        WorkManager.getInstance().enqueue(leaveConversationWorker)

        WorkManager.getInstance(context).getWorkInfoByIdLiveData(leaveConversationWorker.id)
            .observeForever { workInfo: WorkInfo? ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            activity.showSnackbar(
                                String.format(
                                    context.resources.getString(R.string.left_conversation),
                                    conversation.displayName
                                )
                            )
                        }

                        WorkInfo.State.FAILED -> {
                            activity.showSnackbar(context.resources.getString(R.string.nc_common_error_sorry))
                        }

                        else -> {
                        }
                    }
                }
            }

        dismiss()
    }

    private fun deleteConversation() {
        if (!TextUtils.isEmpty(conversation.token)) {
            activity.showDeleteConversationDialog(conversation)
        }

        dismiss()
    }

    private fun chatApiVersion(): Int {
        return ApiUtils.getChatApiVersion(currentUser, intArrayOf(ApiUtils.APIv1))
    }

    companion object {
        val TAG = ConversationsListBottomDialog::class.simpleName
    }
}

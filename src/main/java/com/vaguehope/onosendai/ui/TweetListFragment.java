package com.vaguehope.onosendai.ui;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.vaguehope.onosendai.C;
import com.vaguehope.onosendai.R;
import com.vaguehope.onosendai.config.Account;
import com.vaguehope.onosendai.config.Column;
import com.vaguehope.onosendai.config.Config;
import com.vaguehope.onosendai.config.InlineMediaStyle;
import com.vaguehope.onosendai.config.InternalColumnType;
import com.vaguehope.onosendai.images.ImageLoader;
import com.vaguehope.onosendai.images.ImageLoaderUtils;
import com.vaguehope.onosendai.model.Meta;
import com.vaguehope.onosendai.model.MetaType;
import com.vaguehope.onosendai.model.MetaUtils;
import com.vaguehope.onosendai.model.ScrollState;
import com.vaguehope.onosendai.model.Tweet;
import com.vaguehope.onosendai.model.TweetListCursorAdapter;
import com.vaguehope.onosendai.payload.InReplyToLoaderTask;
import com.vaguehope.onosendai.payload.InReplyToPayload;
import com.vaguehope.onosendai.payload.Payload;
import com.vaguehope.onosendai.payload.PayloadClickListener;
import com.vaguehope.onosendai.payload.PayloadListAdapter;
import com.vaguehope.onosendai.payload.PayloadListClickListener;
import com.vaguehope.onosendai.payload.PayloadType;
import com.vaguehope.onosendai.payload.ReplyLoaderTask;
import com.vaguehope.onosendai.provider.NetworkType;
import com.vaguehope.onosendai.provider.ProviderMgr;
import com.vaguehope.onosendai.provider.RtTask;
import com.vaguehope.onosendai.provider.RtTask.RtRequest;
import com.vaguehope.onosendai.provider.ServiceRef;
import com.vaguehope.onosendai.storage.DbClient;
import com.vaguehope.onosendai.storage.DbInterface;
import com.vaguehope.onosendai.storage.DbInterface.ColumnState;
import com.vaguehope.onosendai.storage.DbInterface.TwUpdateListener;
import com.vaguehope.onosendai.storage.DbProvider;
import com.vaguehope.onosendai.ui.LocalSearchDialog.OnTweetListener;
import com.vaguehope.onosendai.ui.pref.OsPreferenceActivity;
import com.vaguehope.onosendai.update.FetchColumn;
import com.vaguehope.onosendai.update.KvKeys;
import com.vaguehope.onosendai.update.UpdateService;
import com.vaguehope.onosendai.util.DateHelper;
import com.vaguehope.onosendai.util.DialogHelper;
import com.vaguehope.onosendai.util.DialogHelper.Listener;
import com.vaguehope.onosendai.util.LogWrapper;
import com.vaguehope.onosendai.util.NetHelper;
import com.vaguehope.onosendai.util.Result;
import com.vaguehope.onosendai.util.Titleable;
import com.vaguehope.onosendai.util.exec.ExecutorEventListener;
import com.vaguehope.onosendai.util.exec.TrackingAsyncTask;
import com.vaguehope.onosendai.widget.ScrollIndicator;
import com.vaguehope.onosendai.widget.SidebarLayout;

/**
 * https://developer.android.com/intl/fr/guide/components/fragments.html#
 * Creating
 */
public class TweetListFragment extends Fragment implements DbProvider {

	static final String ARG_COLUMN_ID = "column_id";
	static final String ARG_COLUMN_POSITION = "column_pos";
	static final String ARG_COLUMN_TITLE = "column_title";
	static final String ARG_COLUMN_IS_LATER = "column_is_later";
	static final String ARG_COLUMN_SHOW_INLINEMEDIA = "column_show_inlinemedia";

	private final LogWrapper log = new LogWrapper();

	private int columnId = -1;
	private int columnPosition = -1;
	private boolean isLaterColumn;
	private InlineMediaStyle inlineMediaStyle;
	private Config conf;
	private RefreshUiHandler refreshUiHandler;

	private MainActivity mainActivity;
	private SidebarLayout sidebar;
	private ProgressBar prgUpdating;
	private Button btnColumnTitle;
	private ListView tweetList;
	private TextView tweetListStatus;
	private Button tweetListEmptyRefresh;

	private PayloadListAdapter lstTweetPayloadAdaptor;
	private Button btnDetailsLater;

	private ScrollState scrollState;
	private Tweet scrollToTweet;
	private ScrollIndicator scrollIndicator;
	private TweetListCursorAdapter adapter;
	private DbClient bndDb;

	@Override
	public void onCreate (final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);
	}

	@Override
	public View onCreateView (final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
		this.columnId = getArguments().getInt(ARG_COLUMN_ID);
		this.columnPosition = getArguments().getInt(ARG_COLUMN_POSITION);
		this.isLaterColumn = getArguments().getBoolean(ARG_COLUMN_IS_LATER, false);
		this.inlineMediaStyle = InlineMediaStyle.parseJson(getArguments().getString(ARG_COLUMN_SHOW_INLINEMEDIA));
		this.log.setPrefix("C" + this.columnId);
		this.log.d("onCreateView()");

		this.mainActivity = (MainActivity) getActivity();
		this.conf = this.mainActivity.getConf();
		final ImageLoader imageLoader = ImageLoaderUtils.fromActivity(getActivity());

		/*
		 * Fragment life cycles are strange. onCreateView() is called multiple
		 * times before onSaveInstanceState() is called. Do not overwrite
		 * perfectly good stated stored in member var.
		 */
		if (this.scrollState == null) {
			this.scrollState = ScrollState.fromBundle(savedInstanceState);
		}

		final View rootView = inflater.inflate(R.layout.tweetlist, container, false);
		this.sidebar = (SidebarLayout) rootView.findViewById(R.id.tweetListLayout);

		rootView.setFocusableInTouchMode(true);
		rootView.requestFocus();
		rootView.setOnKeyListener(new SidebarLayout.BackButtonListener(this.sidebar));

		((Button) rootView.findViewById(R.id.tweetListGoto)).setOnClickListener(new GotoMenu(this.mainActivity));

		this.btnColumnTitle = (Button) rootView.findViewById(R.id.tweetListTitle);
		this.btnColumnTitle.setTag(getArguments().getString(ARG_COLUMN_TITLE));
		this.btnColumnTitle.setOnClickListener(this.columnTitleClickListener);
		updateScrollLabelToIdle();

		this.prgUpdating = (ProgressBar) rootView.findViewById(R.id.tweetListPrg);

		final Button btnMenu = (Button) rootView.findViewById(R.id.tweetListMenu);
		if (this.isLaterColumn) {
			((ViewGroup) btnMenu.getParent()).removeView(btnMenu);
		}
		else {
			btnMenu.setOnClickListener(this.menuClickListener);
		}

		this.tweetListEmptyRefresh = (Button) rootView.findViewById(R.id.tweetListEmptyRefresh);
		this.tweetListEmptyRefresh.setOnClickListener(this.refreshClickListener);

		this.tweetList = (ListView) rootView.findViewById(R.id.tweetListList);
		this.adapter = new TweetListCursorAdapter(container.getContext(), this.inlineMediaStyle, imageLoader, this.tweetList);
		this.tweetList.setAdapter(this.adapter);
		this.tweetList.setOnItemClickListener(this.tweetItemClickedListener);
		this.tweetList.setEmptyView(this.tweetListEmptyRefresh);
		if (this.inlineMediaStyle == InlineMediaStyle.SEAMLESS) this.tweetList.setDrawSelectorOnTop(true);
		this.refreshUiHandler = new RefreshUiHandler(this);

		final ListView lstTweetPayload = (ListView) rootView.findViewById(R.id.tweetDetailPayloadList);
		this.lstTweetPayloadAdaptor = new PayloadListAdapter(container.getContext(), imageLoader, lstTweetPayload, this.payloadClickListener);
		lstTweetPayload.setAdapter(this.lstTweetPayloadAdaptor);
		lstTweetPayload.setOnItemClickListener(new PayloadListClickListener(this.payloadClickListener));
		((Button) rootView.findViewById(R.id.tweetDetailClose)).setOnClickListener(new SidebarLayout.ToggleSidebarListener(this.sidebar));
		this.btnDetailsLater = (Button) rootView.findViewById(R.id.tweetDetailLater);

		this.scrollIndicator = ScrollIndicator.attach(getActivity(),
				(ViewGroup) rootView.findViewById(R.id.tweetListView),
				this.tweetList, this.tweetListScrollListener);

		this.tweetListStatus = (TextView) rootView.findViewById(R.id.tweetListStatus);
		this.tweetListStatus.setOnClickListener(this.tweetListStatusClickListener);

		return rootView;
	}

	@Override
	public void onDestroy () {
		if (this.adapter != null) this.adapter.dispose();
		if (this.bndDb != null) this.bndDb.dispose();
		super.onDestroy();
	}

	@Override
	public void onResume () {
		super.onResume();
		resumeDb();
		this.mainActivity.onFragmentResumed(getColumnId(), this);
	}

	@Override
	public void onPause () {
		saveScroll();
		saveSavedScrollToDb();
		suspendDb();
		this.mainActivity.onFragmentPaused(getColumnId());
		super.onPause();
	}

	@Override
	public void onSaveInstanceState (final Bundle outState) {
		super.onSaveInstanceState(outState);
		if (this.scrollState != null) this.scrollState.addToBundle(outState);
	}

//	- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

	protected LogWrapper getLog () {
		return this.log;
	}

	protected MainActivity getMainActivity () {
		return this.mainActivity;
	}

	public SidebarLayout getSidebar () {
		return this.sidebar;
	}

	public int getColumnId () {
		return this.columnId;
	}

	public int getColumnPosition () {
		return this.columnPosition;
	}

	protected InlineMediaStyle getInlineMediaStyle () {
		return this.inlineMediaStyle;
	}

	protected Config getConf () {
		return this.conf;
	}

	protected Column getColumn () {
		return this.conf.getColumnById(this.columnId);
	}

	private Account getColumnAccount () {
		return this.conf.getAccount(getColumn().getAccountId());
	}

	protected TweetListCursorAdapter getAdapter () {
		return this.adapter;
	}

	private ProviderMgr getProviderMgr () {
		return getMainActivity().getProviderMgr();
	}

	private ExecutorEventListener getExecutorEventListener () {
		return getMainActivity().getExecutorEventListener();
	}

	private Executor getLocalEs () {
		return getMainActivity().getLocalEs();
	}

	private Executor getNetEs () {
		return getMainActivity().getNetEs();
	}

	private ServiceRef findFullService (final Account account, final ServiceRef svc) {
		final List<ServiceRef> accounts = getProviderMgr().getSuccessWhaleProvider().getPostToAccountsCached(account);
		if (accounts == null) return null;
		for (final ServiceRef a : accounts) {
			if (svc.equals(a)) return a;
		}
		return null;
	}

//	- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

	protected ScrollState getCurrentScroll () {
		return ScrollState.from(this.tweetList, this.scrollIndicator);
	}

	private void saveScroll () {
		final ScrollState newState = getCurrentScroll();
		if (newState != null) {
			this.scrollState = newState;
			this.log.d("Saved scroll: %s", this.scrollState);
		}
	}

	private void saveScrollIfNotSaved () {
		if (this.scrollState != null) return;
		saveScroll();
	}

	private void restoreScroll () {
		if (this.scrollState == null) return;
		this.scrollState.applyTo(this.tweetList, this.scrollIndicator);
		this.log.d("Restored scroll: %s", this.scrollState);
		this.scrollState = null;
	}

	private void saveSavedScrollToDb () {
		final DbInterface db = getDb();
		if (db != null) db.storeScroll(this.columnId, this.scrollState);
	}

	protected void restoreSavedScrollFromDb () {
		if (this.scrollState != null) return;
		this.scrollState = getDb().getScroll(this.columnId);
	}

	protected void scrollTop () {
		this.tweetList.setSelectionAfterHeaderView();
	}

	public void scrollToTweet(final Tweet tweet) {
		if (tweet == null) return;
		if (this.adapter.getCount() > 0) {
			for (int i = 0; i < this.adapter.getCount(); i++) {
				if (this.adapter.getItemId(i) == tweet.getUid()) {
					this.tweetList.setSelectionFromTop(i, 0);
					break;
				}
			}
		}
		else {
			this.scrollToTweet = tweet;
		}
	}

	private void scrollToStashedTweet () {
		if (this.scrollToTweet == null) return;
		final Tweet tweet = this.scrollToTweet;
		this.scrollToTweet = null;
		scrollToTweet(tweet);
	}

//	- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

	private void resumeDb () {
		if (this.bndDb == null) {
			this.log.d("Binding DB service...");
			this.bndDb = new DbClient(getActivity(), this.log.getPrefix(), new Runnable() {
				@Override
				public void run () {
					/*
					 * this convoluted method is because the service connection
					 * won't finish until this thread processes messages again
					 * (i.e., after it exits this thread). if we try to talk to
					 * the DB service before then, it will NPE.
					 */
					getDb().addTwUpdateListener(getGuiUpdateListener());
					restoreSavedScrollFromDb();
					refreshUi();
					getLog().d("DB service bound.");
				}
			});
		}
		else if (getDb() != null) { // because we stop listening in onPause(), we must resume if the user comes back.
			getDb().addTwUpdateListener(getGuiUpdateListener());
			restoreSavedScrollFromDb();
			refreshUi();
			this.log.d("DB service rebound.");
		}
		else {
			this.log.w("resumeDb() called while DB service is half bound.  I do not know what to do.");
		}
	}

	private void suspendDb () {
		if (getDb() != null) { // We might be pausing before the callback has come.
			getDb().removeTwUpdateListener(getGuiUpdateListener());
		}
		else { // If we have not even had the callback yet, cancel it.
			this.bndDb.clearReadyListener();
		}
		this.log.d("DB service released.");
	}

	@Override
	public DbInterface getDb () {
		final DbClient d = this.bndDb;
		if (d == null) return null;
		return d.getDb();
	}

//	- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

	protected void scheduleRefresh (final boolean all) {
		if (!NetHelper.connectionPresent(getActivity())) {
			DialogHelper.alert(getActivity(), "No internet connection available.");
			return;
		}

		final Intent intent = new Intent(getActivity(), UpdateService.class);
		intent.putExtra(UpdateService.ARG_IS_MANUAL, true);
		if (!all) intent.putExtra(UpdateService.ARG_COLUMN_ID, this.columnId);
		getActivity().startService(intent);

		final String msg = all ? "Refresh all columns requested." : "Refresh column requested.";
		Toast.makeText(getActivity(), msg, Toast.LENGTH_SHORT).show();
	}

//	- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

	private final OnClickListener columnTitleClickListener = new OnClickListener() {
		@Override
		public void onClick (final View v) {
			scrollTop();
		}
	};

	private final OnClickListener menuClickListener = new OnClickListener() {
		@Override
		public void onClick (final View v) {
			final PopupMenu popupMenu = new PopupMenu(getActivity(), v);
			popupMenu.getMenuInflater().inflate(R.menu.listmenu, popupMenu.getMenu());
			popupMenu.setOnMenuItemClickListener(TweetListFragment.this.menuItemClickListener);
			popupMenu.show();
		}
	};

	protected final PopupMenu.OnMenuItemClickListener menuItemClickListener = new PopupMenu.OnMenuItemClickListener() {
		@Override
		public boolean onMenuItemClick (final MenuItem item) {
			return menuItemClick(item);
		}
	};

	protected boolean menuItemClick (final MenuItem item) {
		switch (item.getItemId()) {
			case R.id.mnuPost:
				showPost();
				return true;
			case R.id.mnuOutbox:
				showOutbox();
				return true;
			case R.id.mnuRefreshColumnNow:
				scheduleRefresh(false);
				return true;
			case R.id.mnuRefreshAllNow:
				scheduleRefresh(true);
				return true;
			case R.id.mnuPreferences:
				startActivity(new Intent(getActivity(), OsPreferenceActivity.class));
				return true;
			case R.id.mnuLocalSearch:
				showLocalSearch();
				return true;
			default:
				return false;
		}
	}

	private final OnItemClickListener tweetItemClickedListener = new OnItemClickListener() {
		@Override
		public void onItemClick (final AdapterView<?> parent, final View view, final int position, final long id) {
			showTweetDetails(getAdapter().getItemSid(position));
		}
	};

	private final OnScrollListener tweetListScrollListener = new OnScrollListener() {
		private boolean scrolling = false;
		private int lastFirstVisibleItem = -1;

		@Override
		public void onScrollStateChanged (final AbsListView view, final int newScrollState) {
			this.scrolling = (newScrollState != OnScrollListener.SCROLL_STATE_IDLE);
		}

		@Override
		public void onScroll (final AbsListView view, final int firstVisibleItem, final int visibleItemCount, final int totalItemCount) {
			if (this.scrolling || firstVisibleItem != this.lastFirstVisibleItem) {
				onTweetListScroll();
				this.lastFirstVisibleItem = firstVisibleItem;
			}
		}
	};

	private final PayloadClickListener payloadClickListener = new PayloadClickListener() {

		@Override
		public boolean payloadClicked (final Payload payload) {
			return payloadClick(payload);
		}

		@Override
		public void subviewClicked (final Payload payload, final int index) {
			payloadSubviewClick(payload, index);
		}

	};

//	- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

	protected boolean payloadClick (final Payload payload) {
		if (payload.getType() == PayloadType.INREPLYTO) {
			showTweetDetails(((InReplyToPayload) payload).getInReplyToTweet().getSid());
			return true;
		}
		return false;
	}

	protected void payloadSubviewClick (final Payload payload, final int index) {
		if (payload.getType() == PayloadType.SHARE) {
			switch (index) {
				case 0:
					askRt(payload.getOwnerTweet());
					break;
				case 1:
					showPost(payload.getOwnerTweet());
					break;
				default:
			}
		}
	}

	protected void showTweetDetails (final String tweetSid) {
		Tweet tweet = getDb().getTweetDetails(this.columnId, tweetSid);
		if (tweet == null) tweet = getDb().getTweetDetails(tweetSid);
		// TODO better way of showing this error.
		if (tweet == null) tweet = new Tweet(tweetSid, "", "", "Error: tweet with SID=" + tweetSid + " not found.",
				TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()), null, null, Collections.EMPTY_LIST);
		this.lstTweetPayloadAdaptor.setInput(getConf(), tweet);

		new ReplyLoaderTask(getExecutorEventListener(), getActivity(), getDb(), tweet, this.lstTweetPayloadAdaptor).executeOnExecutor(getLocalEs());
		new InReplyToLoaderTask(getExecutorEventListener(), getActivity().getApplicationContext(), getConf(), getProviderMgr(), tweet, getColumn().isHdMedia(), this.lstTweetPayloadAdaptor, getNetEs()).executeOnExecutor(getNetEs());

		setReadLaterButton(tweet, this.isLaterColumn);
		this.sidebar.openSidebar();
	}

	protected void setReadLaterButton (final Tweet tweet, final boolean laterColumn) {
		this.btnDetailsLater.setText(laterColumn ? R.string.btn_tweet_read : R.string.btn_tweet_later);
		this.btnDetailsLater.setOnClickListener(new DetailsLaterClickListener(this, tweet, laterColumn));
	}

	private void showPost () {
		showPost(null);
	}

	private void showPost (final Tweet tweetToQuote) {
		final Intent intent = new Intent(getActivity(), PostActivity.class);
		final Account columnAccount = getColumnAccount();
		if (columnAccount != null) intent.putExtra(PostActivity.ARG_ACCOUNT_ID, columnAccount.getId());
		if (tweetToQuote != null) intent.putExtra(PostActivity.ARG_BODY,
				String.format("RT @%s %s", tweetToQuote.getUsername(), tweetToQuote.getBody()));
		startActivity(intent);
	}

	private void showOutbox () {
		startActivity(new Intent(getActivity(), OutboxActivity.class));
	}

	private void showLocalSearch () {
		LocalSearchDialog.show(getActivity(), this, getMainActivity(), new OnTweetListener() {
			@Override
			public void onTweet (final int colId, final Tweet tweet) {
				getMainActivity().gotoTweet(colId, tweet);
			}
		});
	}

	private void askRt (final Tweet tweet) {
		final Account account = MetaUtils.accountFromMeta(tweet, this.conf);
		if (account == null) {
			DialogHelper.alert(getActivity(), "Can not find this tweet's account metadata.");
			return;
		}

		String action = "RT";
		String as = account.getUiTitle();
		final Meta svcMeta = tweet.getFirstMetaOfType(MetaType.SERVICE);
		if (svcMeta != null) {
			ServiceRef svc = ServiceRef.parseServiceMeta(svcMeta);
			if (svc != null) {
				if (svc.getType() == NetworkType.FACEBOOK) action = "Like";

				final ServiceRef fullSvc = findFullService(account, svc);
				if (fullSvc != null) svc = fullSvc;
				as = String.format("%s via %s", svc.getUiTitle(), as);
			}
		}

		final AlertDialog.Builder dlgBld = new AlertDialog.Builder(getActivity());
		dlgBld.setMessage(String.format("%s as %s?", action, as));

		dlgBld.setPositiveButton("RT", new DialogInterface.OnClickListener() {
			@Override
			public void onClick (final DialogInterface dialog, final int which) {
				doRt(account, tweet);
			}
		});

		dlgBld.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
			@Override
			public void onClick (final DialogInterface dialog, final int whichButton) {
				dialog.cancel();
			}
		});

		dlgBld.show();
	}

	protected void doRt (final Account account, final Tweet tweet) {
		new RtTask(getActivity().getApplicationContext(), new RtRequest(account, tweet)).execute();
	}

	private static class DetailsLaterClickListener implements OnClickListener {

		private final TweetListFragment tweetListFragment;
		private final Context context;
		private final Tweet tweet;
		private final boolean isLaterColumn;

		public DetailsLaterClickListener (final TweetListFragment tweetListFragment, final Tweet tweet, final boolean isLaterColumn) {
			this.tweetListFragment = tweetListFragment;
			this.context = tweetListFragment.getActivity();
			this.tweet = tweet;
			this.isLaterColumn = isLaterColumn;
		}

		@Override
		public void onClick (final View v) {
			if (this.isLaterColumn) {
				new SetLaterTask(this.tweetListFragment, this.tweet, LaterState.READ).executeOnExecutor(this.tweetListFragment.getLocalEs());
			}
			else {
				new SetLaterTask(this.tweetListFragment, this.tweet, LaterState.UNREAD).executeOnExecutor(this.tweetListFragment.getLocalEs());
			}
		}
	}

	public enum LaterState {
		UNREAD,
		READ;
	}

	private static class SetLaterTask extends AsyncTask<Void, Void, Exception> {

		private final TweetListFragment parent;
		private final Tweet tweet;
		private final LaterState laterState;

		public SetLaterTask(final TweetListFragment parent, final Tweet tweet, final LaterState laterState) {
			this.parent = parent;
			this.tweet = tweet;
			this.laterState = laterState;
		}

		@Override
		protected void onPreExecute() {
			this.parent.btnDetailsLater.setEnabled(false);
		}

		@Override
		protected Exception doInBackground(final Void... params) {
			try {
				final Column col = this.parent.getConf().findInternalColumn(InternalColumnType.LATER);
				if (col == null) return new IllegalStateException("Read later column not configured.");
				switch (this.laterState) {
					case UNREAD:
						this.parent.getDb().storeTweets(col, Collections.singletonList(this.tweet.cloneWithCurrentTimestamp()));
						return null;
					case READ:
						this.parent.getDb().deleteTweet(col, this.tweet);
						return null;
					default:
						return new IllegalStateException("Unknown read later state: " + this.laterState);
				}
			}
			catch (final Exception e) { // NOSONAR To report status.
				return e;
			}
		}

		@Override
		protected void onPostExecute(final Exception result) {
			if (result == null) {
				if (this.parent.lstTweetPayloadAdaptor.isForTweet(this.tweet)) this.parent.setReadLaterButton(this.tweet, this.laterState != LaterState.READ);
				switch (this.laterState) {
				case UNREAD:
					Toast.makeText(this.parent.getMainActivity(), "Saved for later.", Toast.LENGTH_SHORT).show();
					break;
				case READ:
					Toast.makeText(this.parent.getMainActivity(), "Removed.", Toast.LENGTH_SHORT).show();
					break;
				default:
					DialogHelper.alert(this.parent.getMainActivity(), "Unknown read later state: " + this.laterState);
				}
			}
			else {
				DialogHelper.alert(this.parent.getMainActivity(), result);
			}
			this.parent.btnDetailsLater.setEnabled(true);
		}

	}

//	- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

	protected TwUpdateListener getGuiUpdateListener () {
		return this.guiUpdateListener;
	}

	private final TwUpdateListener guiUpdateListener = new TwUpdateListener() {

		@Override
		public void columnChanged (final int changeColumnId) {
			if (changeColumnId != getColumnId()) return;
			refreshUi();
		}

		@Override
		public void columnStatus (final int eventColumnId, final ColumnState eventType) {
			if (eventColumnId != getColumnId()) return;
			statusChanged(eventType);
		}

	};

	private static final int MSG_REFRESH = 1;
	private static final int MSG_UPDATE_RUNNING = 2;
	private static final int MSG_UPDATE_OVER = 3;
	private static final int MSG_STILL_SCROLLING_CHECK = 4;

	protected void refreshUi () {
		this.refreshUiHandler.sendEmptyMessage(MSG_REFRESH);
	}

	protected void statusChanged (final ColumnState state) {
		switch (state) {
			case UPDATE_RUNNING:
				this.refreshUiHandler.sendEmptyMessage(MSG_UPDATE_RUNNING);
				break;
			case UPDATE_OVER:
				this.refreshUiHandler.sendEmptyMessage(MSG_UPDATE_OVER);
				break;
			default:
		}
	}

	private static class RefreshUiHandler extends Handler {

		private final WeakReference<TweetListFragment> parentRef;

		public RefreshUiHandler (final TweetListFragment parent) {
			this.parentRef = new WeakReference<TweetListFragment>(parent);
		}

		@Override
		public void handleMessage (final Message msg) {
			final TweetListFragment parent = this.parentRef.get();
			if (parent != null) parent.msgOnUiThread(msg);
		}
	}

	protected void msgOnUiThread (final Message msg) {
		switch (msg.what) {
			case MSG_REFRESH:
				refreshUiOnUiThread();
				break;
			case MSG_UPDATE_RUNNING:
				progressIndicator(true);
				this.tweetListEmptyRefresh.setEnabled(false);
				break;
			case MSG_UPDATE_OVER:
				progressIndicator(false);
				this.tweetListEmptyRefresh.setEnabled(true);
				redrawLastUpdateError();
				break;
			case MSG_STILL_SCROLLING_CHECK:
				checkIfTweetListStillScrolling();
				break;
			default:
		}
	}

	private int progressIndicatorCounter = 0;

	/**
	 * Only call on UI thread.
	 */
	protected void progressIndicator (final boolean inProgress) {
		this.progressIndicatorCounter += (inProgress ? 1 : -1);
		this.prgUpdating.setVisibility(this.progressIndicatorCounter > 0 ? View.VISIBLE : View.GONE);
	}

//	- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

	private void refreshUiOnUiThread () {
		new LoadTweets(getExecutorEventListener(), this).executeOnExecutor(getLocalEs());
	}

	private static class LoadTweets extends TrackingAsyncTask<Void, Void, Result<Cursor>> {

		private final TweetListFragment host;

		public LoadTweets (final ExecutorEventListener eventListener, final TweetListFragment host) {
			super(eventListener);
			this.host = host;
		}

		@Override
		public String toString () {
			return "loadTweets:" + this.host.getColumnPosition() + ":" + this.host.getColumn().getTitle();
		}

		@Override
		protected void onPreExecute () {
			this.host.progressIndicator(true);
			this.host.tweetListEmptyRefresh.setEnabled(false);
		}

		@Override
		protected Result<Cursor> doInBackgroundWithTracking (final Void... params) {
			try {
				final DbInterface db = this.host.getDb();
				if (db != null) {
					final Cursor cursor = db.getTweetsCursor(
							this.host.getColumnId(),
							this.host.getColumn().getExcludeColumnIds(),
							this.host.getInlineMediaStyle() == InlineMediaStyle.SEAMLESS);
					return new Result<Cursor>(cursor);
				}
				return new Result<Cursor>(new IllegalStateException("Failed to refresh column as DB was not bound."));
			}
			catch (final Exception e) { // NOSONAR needed to report errors.
				return new Result<Cursor>(e);
			}
		}

		@Override
		protected void onPostExecute (final Result<Cursor> result) {
			if (result.isSuccess()) {
				this.host.saveScrollIfNotSaved();
				this.host.getAdapter().changeCursor(result.getData());
				this.host.getLog().d("Refreshed tweets cursor.");
				this.host.restoreScroll();
				this.host.scrollToStashedTweet();
				this.host.redrawLastUpdateError();
			}
			else {
				this.host.getLog().w("Failed to refresh column.", result.getE());
			}
			this.host.progressIndicator(false);
			this.host.tweetListEmptyRefresh.setEnabled(true);
		}

	}

	private final OnClickListener refreshClickListener = new OnClickListener() {
		@Override
		public void onClick (final View v) {
			scheduleRefresh(false);
		}
	};

//	- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

	private long lastScrollFirstVisiblePosition = -1;
	private long lastScrollTime = 0L;

	protected void onTweetListScroll () {
		final int position = this.tweetList.getFirstVisiblePosition();
		if (position < 0 || position == this.lastScrollFirstVisiblePosition) return;
		this.lastScrollFirstVisiblePosition = position;

		final long now = System.currentTimeMillis();
		this.lastScrollTime = now;
		this.refreshUiHandler.sendEmptyMessageDelayed(MSG_STILL_SCROLLING_CHECK, C.SCROLL_TIME_LABEL_TIMEOUT_MILLIS);

		final long time = this.adapter.getItemTime(position);
		if (time > 0L) {
			this.btnColumnTitle.setText(DateHelper.friendlyAbsoluteDate(getActivity(), now, TimeUnit.SECONDS.toMillis(time)));
		}
	}

	private void checkIfTweetListStillScrolling () {
		if (this.lastScrollTime < 1L || System.currentTimeMillis() - this.lastScrollTime >= C.SCROLL_TIME_LABEL_TIMEOUT_MILLIS) {
			updateScrollLabelToIdle();
		}
	}

	private void updateScrollLabelToIdle () {
		this.btnColumnTitle.setText(String.valueOf(this.btnColumnTitle.getTag()));
		this.lastScrollFirstVisiblePosition = -1;
	}

//	- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

	private void redrawLastUpdateError () {
		final String msg;
		final DbInterface db = getDb();
		if (db != null) {
			msg = db.getValue(KvKeys.KEY_PREFIX_COL_LAST_REFRESH_ERROR + this.columnId);
		}
		else {
			msg = "Can not get last update status; DB not connected.";
			this.log.w(msg);
		}
		if (msg != null) {
			this.tweetListStatus.setText(msg);
			this.tweetListStatus.setVisibility(View.VISIBLE);
		}
		else {
			this.tweetListStatus.setVisibility(View.GONE);
			this.tweetListStatus.setText("");
		}
	}

	protected void popupLastUpdateError () {
		DialogHelper.alert(getActivity(), this.tweetListStatus.getText().toString());
	}

	protected void dismissLastUpdateError () {
		FetchColumn.storeDismiss(getDb(), getColumn());
		redrawLastUpdateError();
	}

	protected void copyLastUpdateError () {
		final ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
		clipboard.setPrimaryClip(ClipData.newPlainText("Error Message", this.tweetListStatus.getText().toString()));
	}

	private enum ErrorMessageAction implements Titleable {
		VIEW("View") {
			@Override
			public void onClick (final TweetListFragment tlf) {
				tlf.popupLastUpdateError();
			}
		},
		COPY("Copy") {
			@Override
			public void onClick (final TweetListFragment tlf) {
				tlf.copyLastUpdateError();
			}
		},
		DISMISS("Dismiss") {
			@Override
			public void onClick (final TweetListFragment tlf) {
				tlf.dismissLastUpdateError();
			}
		};

		private final String title;

		private ErrorMessageAction (final String title) {
			this.title = title;
		}

		@Override
		public String getUiTitle () {
			return this.title;
		}

		public abstract void onClick (TweetListFragment tlf);
	}

	private final OnClickListener tweetListStatusClickListener = new OnClickListener() {
		@Override
		public void onClick (final View v) {
			DialogHelper.askItem(getActivity(), "Message", ErrorMessageAction.values(), new Listener<ErrorMessageAction>() {
				@Override
				public void onAnswer (final ErrorMessageAction answer) {
					answer.onClick(TweetListFragment.this);
				}
			});
		}
	};

}

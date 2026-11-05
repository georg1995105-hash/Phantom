/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.emoji;

import java.util.HashMap;
import java.util.Map;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.keyboard.Key;
import helium314.keyboard.keyboard.Keyboard;
import helium314.keyboard.keyboard.KeyboardActionListener;
import helium314.keyboard.keyboard.KeyboardElement;
import helium314.keyboard.keyboard.KeyboardLayoutSet;
import helium314.keyboard.keyboard.KeyboardSwitcher;
import helium314.keyboard.keyboard.KeyboardView;
import helium314.keyboard.keyboard.MainKeyboardView;
import helium314.keyboard.keyboard.PointerTracker;
import helium314.keyboard.keyboard.internal.KeyDrawParams;
import helium314.keyboard.keyboard.internal.KeyVisualAttributes;
import helium314.keyboard.keyboard.internal.keyboard_parser.EmojiParserKt;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.AudioAndHapticFeedbackManager;
import helium314.keyboard.latin.dictionary.Dictionary;
import helium314.keyboard.latin.dictionary.DictionaryFactory;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.RichInputMethodSubtype;
import helium314.keyboard.latin.SingleDictionaryFacilitator;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.utils.DictionaryInfoUtils;
import helium314.keyboard.latin.utils.ResourceUtils;

import static helium314.keyboard.latin.common.Constants.NOT_A_COORDINATE;

/**
 * View class to implement Emoji palettes.
 * The Emoji keyboard consists of group of views layout/emoji_palettes_view.
 * <ol>
 * <li> Emoji category tabs.
 * <li> Delete button.
 * <li> Emoji keyboard pages that can be scrolled by swiping horizontally or by selecting a tab.
 * <li> Back to main keyboard button and enter button.
 * </ol>
 * Because of the above reasons, this class doesn't extend {@link KeyboardView}.
 */
public final class EmojiPalettesView extends LinearLayout
        implements View.OnClickListener, EmojiViewCallback {
    private static final class PagerViewHolder extends RecyclerView.ViewHolder {
        private long mCategoryId;

        private PagerViewHolder(View itemView) {
            super(itemView);
        }
    }

    private final class PagerAdapter extends RecyclerView.Adapter<PagerViewHolder> {
        private boolean mInitialized;
        private final Map<Integer, RecyclerView> mViews = new HashMap<>(mEmojiCategory.getShownCategories().size());

        private PagerAdapter(ViewPager2 pager) {
            setHasStableIds(true);
            pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    var categoryId = (int) getItemId(position);
                    setCurrentCategory(EmojiCategory.Category.getEntries().get(categoryId), false);
                    var recyclerView = mViews.get(position);
                    if (recyclerView != null) {
                        updateState(recyclerView, categoryId);
                    }
                }
            });
        }

        @Override
        public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
            recyclerView.setItemViewCacheSize(mEmojiCategory.getShownCategories().size());
        }

        @NonNull
        @Override
        public PagerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            var view = LayoutInflater.from(parent.getContext()).inflate(R.layout.emoji_category_view, parent, false);
            var viewHolder = new PagerViewHolder(view);
            var emojiRecyclerView = getRecyclerView(view);

            emojiRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);
                    // Ignore this message. Only want the actual page selected.
                }

                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    updateState(recyclerView, viewHolder.mCategoryId);
                }
            });

            emojiRecyclerView.setPersistentDrawingCache(PERSISTENT_NO_CACHE);
            return viewHolder;
        }

        @Override
        public void onBindViewHolder(PagerViewHolder holder, int position) {
            holder.mCategoryId = getItemId(position);
            var recyclerView = getRecyclerView(holder.itemView);
            mViews.put(position, recyclerView);
            recyclerView.setAdapter(new EmojiPalettesAdapter(
                mEmojiCategory,
                EmojiCategory.Category.getEntries().get((int) holder.mCategoryId),
                EmojiPalettesView.this
            ));

            if (! mInitialized) {
                recyclerView.scrollToPosition(mEmojiCategory.getCurrentCategoryPageId());
                mInitialized = true;
            }
        }

        @Override
        public int getItemCount() {
            return mEmojiCategory.getShownCategories().size();
        }

        @Override
        public void onViewDetachedFromWindow(PagerViewHolder holder) {
            if (holder.mCategoryId == EmojiCategory.Category.RECENTS.ordinal()) {
                // Needs to save pending updates for recent keys when we get out of the recents
                // category because we don't want to move the recent emojis around while the user
                // is in the recents category.
                getRecentsKeyboard().flushPendingRecentKeys();
                getRecyclerView(holder.itemView).getAdapter().notifyDataSetChanged();
            }
        }

        @Override
        public long getItemId(int position) {
            return mEmojiCategory.getShownCategories().get(position).getCategory().ordinal();
        }

        private static RecyclerView getRecyclerView(View view) {
            return view.findViewById(R.id.emoji_keyboard_list);
        }

        private void updateState(@NonNull RecyclerView recyclerView, long categoryId) {
            if (categoryId != mEmojiCategory.getCurrentCategory().ordinal()) {
                return;
            }

            final int offset = recyclerView.computeVerticalScrollOffset();
            final int extent = recyclerView.computeVerticalScrollExtent();
            final int range = recyclerView.computeVerticalScrollRange();
            final float percentage = offset / (float) (range - extent);

            final int currentCategorySize = mEmojiCategory.getCurrentCategoryPageCount();
            final int a = (int) (percentage * currentCategorySize);
            final float b = percentage * currentCategorySize - a;
            mEmojiCategoryPageIndicatorView.setCategoryPageId(currentCategorySize, a, b);

            LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
            final int firstCompleteVisibleBoard = layoutManager.findFirstCompletelyVisibleItemPosition();
            final int firstVisibleBoard = layoutManager.findFirstVisibleItemPosition();
            mEmojiCategory.setCurrentCategoryPageId(
                    firstCompleteVisibleBoard > 0 ? firstCompleteVisibleBoard : firstVisibleBoard);
        }
    }

    private static SingleDictionaryFacilitator sDictionaryFacilitator;

    private boolean initialized = false;
    private final Colors mColors;
    private final EmojiLayoutParams mEmojiLayoutParams;
    private LinearLayout mTabStrip;
    private EmojiCategoryPageIndicatorView mEmojiCategoryPageIndicatorView;
    private KeyboardActionListener mKeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER;
    private final EmojiCategory mEmojiCategory;
    private ViewPager2 mPager;

    public EmojiPalettesView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.emojiPalettesViewStyle);
    }

    public EmojiPalettesView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);
        mColors = Settings.getValues().mColors;
        final KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(context, null, null);
        final Resources res = context.getResources();
        mEmojiLayoutParams = new EmojiLayoutParams(res);
        builder.setSubtype(RichInputMethodSubtype.Companion.getEmojiSubtype());
        builder.setKeyboardGeometry(ResourceUtils.getKeyboardWidth(context, Settings.getValues()),
                mEmojiLayoutParams.getEmojiKeyboardHeight());
        final KeyboardLayoutSet layoutSet = builder.build();
        final TypedArray emojiPalettesViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.EmojiPalettesView, defStyle, R.style.EmojiPalettesView);
        mEmojiCategory = new EmojiCategory(context, layoutSet, emojiPalettesViewAttr);
        emojiPalettesViewAttr.recycle();
        setFitsSystemWindows(true);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final Resources res = getContext().getResources();
        // The main keyboard expands to the entire this {@link KeyboardView}.
        final int width = ResourceUtils.getKeyboardWidth(getContext(), Settings.getValues())
                + getPaddingLeft() + getPaddingRight();
        final int height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues())
                + getPaddingTop() + getPaddingBottom();
        mEmojiCategoryPageIndicatorView.mWidth = width;
        setMeasuredDimension(width, height);
    }

    private void addTab(LinearLayout host, EmojiCategory.Category category) {
        final ImageView iconView = new ImageView(getContext());
        mColors.setBackground(iconView, ColorType.STRIP_BACKGROUND);
        mColors.setColor(iconView, ColorType.EMOJI_CATEGORY);
        iconView.setScaleType(ImageView.ScaleType.CENTER);
        iconView.setImageResource(switch (category) {
            case RECENTS -> R.drawable.ic_sv_recent;
            case SMILEYS -> R.drawable.ic_sv_emoji;
            case PEOPLE -> R.drawable.ic_sv_people;
            case NATURE -> R.drawable.ic_sv_nature;
            case FOOD -> R.drawable.ic_sv_food;
            case TRAVEL_PLACES -> R.drawable.ic_sv_travel;
            case ACTIVITIES -> R.drawable.ic_sv_activity;
            case OBJECTS -> R.drawable.ic_sv_object;
            case SYMBOLS -> R.drawable.ic_sv_apps;
            case FLAGS -> R.drawable.ic_sv_flag;
            case EMOTICONS -> R.drawable.ic_sv_emoticon;
        });
        iconView.setContentDescription(mEmojiCategory.getAccessibilityDescription(category));
        iconView.setTag(category);
        host.addView(iconView);
        iconView.setLayoutParams(new LinearLayout.LayoutParams((int) (44 * getResources().getDisplayMetrics().density), ViewGroup.LayoutParams.MATCH_PARENT));
        iconView.setOnClickListener(this);
        if (category == EmojiCategory.Category.RECENTS) {
            iconView.setOnLongClickListener(v -> {
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_LONG_PRESS);
                clearRecentKeys();

                return true;
            });
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    public void initialize() { // needs to be delayed for access to EmojiTabStrip, which is not a child of this view
        if (initialized) return;
        mEmojiCategory.initialize();
        mTabStrip = (LinearLayout) KeyboardSwitcher.getInstance().getEmojiTabStrip();
        if (Settings.getValues().isSecondaryStripVisible()) {
            mTabStrip.removeAllViews();
            final var ui = helium314.keyboard.smart.ui.SvUi.INSTANCE;
            final int buttonWidth = ui.dp(getContext(), 44);
            mTabStrip.addView(ui.iconButton(getContext(), R.drawable.ic_sv_back, getContext().getString(R.string.sv_back), 44,
                v -> { mKeyboardActionListener.onCodeInput(KeyCode.ALPHA, NOT_A_COORDINATE, NOT_A_COORDINATE, false); return kotlin.Unit.INSTANCE; }),
                new LinearLayout.LayoutParams(buttonWidth, ViewGroup.LayoutParams.MATCH_PARENT));
            android.widget.TextView search = ui.chip(getContext(), getContext().getString(R.string.sv_emoji_search), false);
            search.setCompoundDrawablesRelativeWithIntrinsicBounds(ui.icon(getContext(), R.drawable.sym_keyboard_search_lxx, ui.text()), null, null, null);
            search.setCompoundDrawablePadding(ui.dp(getContext(), 4));
            search.setTextSize(12);
            search.setPadding(ui.dp(getContext(), 8), 0, ui.dp(getContext(), 6), 0);
            search.setOnClickListener(v -> openSearch());
            mTabStrip.addView(search, new LinearLayout.LayoutParams(ui.dp(getContext(), 102), ui.dp(getContext(), 38)));
            LinearLayout categories = new LinearLayout(getContext());
            android.widget.HorizontalScrollView categoryScroll = new android.widget.HorizontalScrollView(getContext());
            categoryScroll.setHorizontalScrollBarEnabled(false);
            categoryScroll.addView(categories);
            mTabStrip.addView(categoryScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            for (EmojiCategory.CategoryProperties properties : mEmojiCategory.getShownCategories()) {
                addTab(categories, properties.getCategory());
            }
        }

        mPager = findViewById(R.id.emoji_pager);
        mPager.setAdapter(new PagerAdapter(mPager));
        mEmojiLayoutParams.setEmojiListProperties(mPager);
        mEmojiCategoryPageIndicatorView = findViewById(R.id.emoji_category_page_id_view);
        mEmojiLayoutParams.setCategoryPageIdViewProperties(mEmojiCategoryPageIndicatorView);
        setCurrentCategory(mEmojiCategory.getCurrentCategory(), true);
        mEmojiCategoryPageIndicatorView.setColors(mColors.get(ColorType.EMOJI_CATEGORY_SELECTED), mColors.get(ColorType.STRIP_BACKGROUND));
        initialized = true;
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link android.view.View.OnClickListener}
     * interface to handle non-canceled touch-up events from View-based elements such as the space
     * bar.
     */
    @Override
    public void onClick(View v) {
        final Object tag = v.getTag();
        if (tag instanceof EmojiCategory.Category category) {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS);
            if (category != mEmojiCategory.getCurrentCategory()) {
                setCurrentCategory(category, false);
                updateEmojiCategoryPageIdView();
            }
        }
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link EmojiViewCallback}
     * interface to handle touch events from non-View-based elements such as Emoji buttons.
     */
    @Override
    public void onPressKey(final Key key) {
        final int code = key.getCode();
        mKeyboardActionListener.onPressKey(code, 0, 1, HapticEvent.KEY_PRESS);
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link EmojiViewCallback}
     * interface to handle touch events from non-View-based elements such as Emoji buttons.
     * This may be called without any prior call to {@link EmojiViewCallback#onPressKey(Key)}.
     */
    @Override
    public void onReleaseKey(final Key key) {
        addRecentKey(key);
        final int code = key.getCode();
        if (code == KeyCode.MULTIPLE_CODE_POINTS) {
            // todo: when we enter some emoticons, e.g. :-D, inline emoji search is triggered and emoji view is closed
            //  (one more instance of "emoji search should recognize emoticons", but in this case could be fixed in other ways)
            mKeyboardActionListener.onTextInput(key.getOutputText());
        } else if (code != KeyCode.UNSPECIFIED) {
            mKeyboardActionListener.onCodeInput(code, NOT_A_COORDINATE, NOT_A_COORDINATE, false);
        }
        mKeyboardActionListener.onReleaseKey(code, false);
        if (Settings.getValues().mAlphaAfterEmojiInEmojiView)
            mKeyboardActionListener.onCodeInput(KeyCode.ALPHA, NOT_A_COORDINATE, NOT_A_COORDINATE, false);
    }

    @Override
    public void onRemoveRecentsKey(Key key)
    {
        getRecentsKeyboard().removeRecentsKey(key);
    }

    private void clearRecentKeys() {
        if (RecentEmojis.get().isEmpty()) return;
        RecentEmojis.clear();
        getRecentsKeyboard().removeAllKeys();
        mPager.getAdapter().notifyItemChanged(mEmojiCategory.getRecentTabId());
        KeyboardSwitcher.getInstance().showToast(getResources().getString(R.string.recent_emojis_cleared), true);
    }

    @Override
    public String getDescription(String emoji) {
        if (sDictionaryFacilitator == null) {
            return null;
        }

        var wordProperty = sDictionaryFacilitator.getWordProperty(EmojiParserKt.getEmojiNeutralVersion(emoji));
        if (wordProperty == null || ! wordProperty.mHasShortcuts) {
            return null;
        }

        return wordProperty.mShortcutTargets.get(0).mWord;
    }

    public void setHardwareAcceleratedDrawingEnabled(final boolean enabled) {
        if (!enabled) return;
        // TODO: Should use LAYER_TYPE_SOFTWARE when hardware acceleration is off?
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void startEmojiPalettes(final KeyVisualAttributes keyVisualAttr,
               final EditorInfo editorInfo, final KeyboardActionListener keyboardActionListener) {
        initialize();

        setupBottomRowKeyboard(editorInfo, keyboardActionListener);
        final KeyDrawParams params = new KeyDrawParams();
        params.updateParams(mEmojiLayoutParams.getBottomRowKeyboardHeight(), keyVisualAttr);
        new EmojiLayoutParams(getResources()).setEmojiListProperties(mPager); // necessary when floating
        mEmojiCategory.reloadRecents(); // in case recents changed from outside emoji keyboards
        setupSidePadding();
        initDictionaryFacilitator();
        updateRecentStrip();
        updateBottomSelection(mEmojiCategory.getCurrentCategory());
    }

    private void updateRecentStrip() {
        LinearLayout row = findViewById(R.id.sv_emoji_recent);
        row.removeAllViews();
        if (mEmojiCategory.getCurrentCategory() == EmojiCategory.Category.EMOTICONS) {
            android.widget.TextView title = helium314.keyboard.smart.ui.SvUi.INSTANCE.label(getContext(), 12f,
                    helium314.keyboard.smart.ui.SvUi.INSTANCE.secondaryText(), false);
            title.setText(R.string.sv_text_emoticons_title);
            row.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        var ui = helium314.keyboard.smart.ui.SvUi.INSTANCE;
        android.widget.TextView label = ui.label(getContext(), 12f, ui.secondaryText(), false);
        label.setText(R.string.sv_emoji_recent);
        label.setOnClickListener(v -> setCurrentCategory(EmojiCategory.Category.RECENTS, false));
        row.addView(label, new LinearLayout.LayoutParams(ui.dp(getContext(), 80), ViewGroup.LayoutParams.MATCH_PARENT));
        for (String emoji : RecentEmojis.get().subList(0, Math.min(8, RecentEmojis.get().size()))) {
            android.widget.TextView item = ui.label(getContext(), 23f, ui.text(), false);
            item.setText(emoji);
            item.setContentDescription(emoji);
            item.setOnClickListener(v -> mKeyboardActionListener.onTextInput(emoji));
            row.addView(item, new LinearLayout.LayoutParams(ui.dp(getContext(), 40), ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void updateBottomSelection(EmojiCategory.Category category) {
        var ui = helium314.keyboard.smart.ui.SvUi.INSTANCE;
        for (int id : new int[] { R.id.sv_emoji_mode, R.id.sv_emoji_space }) {
            android.widget.TextView button = findViewById(id);
            if (button == null) continue;
            boolean selected = (id == R.id.sv_emoji_space) == (category == EmojiCategory.Category.EMOTICONS);
            int color = selected ? ui.onAccent() : ui.text();
            button.setTextColor(color);
            for (android.graphics.drawable.Drawable icon : button.getCompoundDrawablesRelative())
                if (icon != null) icon.mutate().setTint(color);
            float radius = ui.dp(getContext(), 12);
            button.setBackground(ui.ripple(ui.surface(selected ? ui.accent() : ui.key(), radius), radius));
        }
    }

    public void openSearch() {
                java.util.List<Key> keys = new java.util.ArrayList<>();
                for (var props : mEmojiCategory.getShownCategories()) {
                    var cat = props.getCategory();
                    if (cat == EmojiCategory.Category.RECENTS || cat == EmojiCategory.Category.EMOTICONS) continue;
                    for (int page = 0; page < mEmojiCategory.getCategoryPageCount(cat); page++)
                        keys.addAll(mEmojiCategory.getKeyboard(cat, page).getSortedKeys());
                }
                helium314.keyboard.smart.ui.SmartUi.openEmojiSearch(keys, this);
    }

    private void addRecentKey(Key key) {
        if (key.getLabel() == null || !helium314.keyboard.latin.common.EmojiKt.getSingleEmojiRegex().matches(key.getLabel())) return;
        if (Settings.getValues().mIncognitoModeEnabled) {
            // We do not want to log recent keys while being in incognito
            return;
        }
        if (mEmojiCategory.isInRecentTab()) {
            getRecentsKeyboard().addPendingKey(key);
            return;
        }
        getRecentsKeyboard().addKeyFirst(key);
        mPager.getAdapter().notifyItemChanged(mEmojiCategory.getRecentTabId());
        updateRecentStrip();
    }

    private void setupBottomRowKeyboard(EditorInfo editorInfo, KeyboardActionListener keyboardActionListener) {
        // Explicit keyboard-theme colors: the system Activity theme can be light while
        // the IME is dark, making textColorPrimary invisible on this panel.
        for (int id : new int[]{R.id.sv_emoji_back, R.id.sv_emoji_mode, R.id.sv_emoji_space, R.id.sv_emoji_delete}) {
            android.widget.TextView action = findViewById(id);
            action.setTextColor(helium314.keyboard.smart.ui.SvUi.INSTANCE.text());
            action.setBackground(helium314.keyboard.smart.ui.SvUi.INSTANCE.ripple(
                helium314.keyboard.smart.ui.SvUi.INSTANCE.surface(
                    id == R.id.sv_emoji_mode ? helium314.keyboard.smart.ui.SvUi.INSTANCE.accent() : helium314.keyboard.smart.ui.SvUi.INSTANCE.key(), 18f), 18f));
            if (id == R.id.sv_emoji_mode) {
                action.setText("");
                action.setCompoundDrawablesWithIntrinsicBounds(helium314.keyboard.smart.ui.SvUi.INSTANCE.icon(getContext(), R.drawable.ic_sv_emoji, helium314.keyboard.smart.ui.SvUi.INSTANCE.onAccent()), null, null, null);
            }
        }
        findViewById(R.id.sv_emoji_actions).setBackgroundColor(helium314.keyboard.smart.ui.SvUi.INSTANCE.panelBackground());
        findViewById(R.id.sv_emoji_back).setOnClickListener(view ->
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, NOT_A_COORDINATE, NOT_A_COORDINATE, false));
        findViewById(R.id.sv_emoji_mode).setOnClickListener(view -> setCurrentCategory(EmojiCategory.Category.SMILEYS, false));
        findViewById(R.id.sv_emoji_space).setOnClickListener(view -> setCurrentCategory(EmojiCategory.Category.EMOTICONS, false));
        boolean hasEmoticons = mEmojiCategory.getShownCategories().stream().anyMatch(p -> p.getCategory() == EmojiCategory.Category.EMOTICONS);
        findViewById(R.id.sv_emoji_space).setVisibility(hasEmoticons ? VISIBLE : GONE);
        findViewById(R.id.sv_emoji_delete).setOnClickListener(view ->
            keyboardActionListener.onCodeInput(KeyCode.DELETE, NOT_A_COORDINATE, NOT_A_COORDINATE, false));
    }

    private void setupSidePadding() {
        final SettingsValues sv = Settings.getValues();
        final int keyboardWidth = ResourceUtils.getKeyboardWidth(getContext(), sv);
        final TypedArray keyboardAttr = getContext().obtainStyledAttributes(
                null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard);
        final float leftPadding = keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
                keyboardWidth, keyboardWidth, 0f) * sv.mSidePaddingScale;
        final float rightPadding =  keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
                keyboardWidth, keyboardWidth, 0f) * sv.mSidePaddingScale;
        keyboardAttr.recycle();
        mPager.setPadding(
                (int) leftPadding,
                mPager.getPaddingTop(),
                (int) rightPadding,
                mPager.getPaddingBottom()
        );
        mEmojiCategoryPageIndicatorView.setPadding(
                (int) leftPadding,
                mEmojiCategoryPageIndicatorView.getPaddingTop(),
                (int) rightPadding,
                mEmojiCategoryPageIndicatorView.getPaddingBottom()
        );
        // setting width does not do anything, so we have some workaround in EmojiCategoryPageIndicatorView
    }

    public void stopEmojiPalettes() {
        if (!initialized) return;
        getRecentsKeyboard().flushPendingRecentKeys();
    }

    private DynamicGridKeyboard getRecentsKeyboard() {
        return mEmojiCategory.getKeyboard(EmojiCategory.Category.RECENTS, 0);
    }

    public void setKeyboardActionListener(final KeyboardActionListener listener) {
        mKeyboardActionListener = listener;
    }

    private void updateEmojiCategoryPageIdView() {
        if (mEmojiCategoryPageIndicatorView == null) {
            return;
        }
        mEmojiCategoryPageIndicatorView.setCategoryPageId(
                mEmojiCategory.getCurrentCategoryPageCount(),
                mEmojiCategory.getCurrentCategoryPageId(), 0.0f);
    }

    private void setCurrentCategory(EmojiCategory.Category category, boolean initial) {
        EmojiCategory.Category oldCategory = mEmojiCategory.getCurrentCategory();
        if (initial || oldCategory != category) {
            mEmojiCategory.setCurrentCategory(category);
            updateRecentStrip();
            updateBottomSelection(category);

            if (mPager.getScrollState() != ViewPager2.SCROLL_STATE_DRAGGING) {
                // Not swiping
                mPager.setCurrentItem(mEmojiCategory.getTabIdFromCategoryId(
                                mEmojiCategory.getCurrentCategory()), ! initial && ! isAnimationsDisabled());
            }

            if (Settings.getValues().isSecondaryStripVisible()) {
                View old = mTabStrip.findViewWithTag(oldCategory);
                View current = mTabStrip.findViewWithTag(category);

                if (old instanceof ImageView) {
                    Settings.getValues().mColors.setColor((ImageView) old, ColorType.EMOJI_CATEGORY);
                    old.setBackgroundColor(mColors.get(ColorType.STRIP_BACKGROUND));
                    old.setSelected(false);
                }
                if (current instanceof ImageView) {
                    Settings.getValues().mColors.setColor((ImageView) current, ColorType.EMOJI_CATEGORY_SELECTED);
                    current.setBackground(helium314.keyboard.smart.ui.SvUi.INSTANCE.surface(
                        helium314.keyboard.smart.ui.SvUi.INSTANCE.accent(), helium314.keyboard.smart.ui.SvUi.INSTANCE.dp(getContext(), 22)));
                    ((ImageView) current).setColorFilter(helium314.keyboard.smart.ui.SvUi.INSTANCE.onAccent());
                    current.setSelected(true);
                }
            }
        }
    }

    private boolean isAnimationsDisabled() {
        return android.provider.Settings.Global.getFloat(getContext().getContentResolver(),
                                                         android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0.0f;
    }

    public void clearKeyboardCache() {
        if (!initialized) {
            return;
        }

        mEmojiCategory.clearKeyboardCache();
        mPager.getAdapter().notifyDataSetChanged();
        closeDictionaryFacilitator();
    }

    private void initDictionaryFacilitator() {
        if (Settings.getValues().mShowEmojiDescriptions) {
            var locale = RichInputMethodManager.getInstance().getCurrentSubtype().getLocale();
            if (sDictionaryFacilitator == null || ! sDictionaryFacilitator.isForLocale(locale)) {
                closeDictionaryFacilitator();
                var dictFile = DictionaryInfoUtils.getCachedDictForLocaleAndType(locale, Dictionary.TYPE_EMOJI, getContext());
                var dictionary = dictFile != null? DictionaryFactory.getDictionary(dictFile, locale) : null;
                sDictionaryFacilitator = dictionary != null? new SingleDictionaryFacilitator(dictionary) : null;
            }
        } else {
            closeDictionaryFacilitator();
        }
    }

    public static void closeDictionaryFacilitator() {
        if (sDictionaryFacilitator != null) {
            sDictionaryFacilitator.closeDictionaries();
            sDictionaryFacilitator = null;
        }
    }
}

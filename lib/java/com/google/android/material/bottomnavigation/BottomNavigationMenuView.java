/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.material.bottomnavigation;

import com.google.android.material.R;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import androidx.annotation.Dimension;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.StyleRes;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.internal.TextScale;
import androidx.core.util.Pools;
import androidx.core.view.ViewCompat;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.view.menu.MenuItemImpl;
import androidx.appcompat.view.menu.MenuView;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;
import java.util.HashSet;

/** @hide For internal use only. */
@RestrictTo(LIBRARY_GROUP)
public class BottomNavigationMenuView extends ViewGroup implements MenuView {
  private static final long ACTIVE_ANIMATION_DURATION_MS = 115L;
  private static final int ITEM_POOL_SIZE = 5;

  private static final int[] CHECKED_STATE_SET = {android.R.attr.state_checked};
  private static final int[] DISABLED_STATE_SET = {-android.R.attr.state_enabled};

  private final TransitionSet set;
  private final int inactiveItemMaxWidth;
  private final int inactiveItemMinWidth;
  private final int activeItemMaxWidth;
  private final int activeItemMinWidth;
  private final int itemHeight;
  private final OnClickListener onClickListener;
  private final Pools.Pool<BottomNavigationItemView> itemPool =
      new Pools.SynchronizedPool<>(ITEM_POOL_SIZE);

  private boolean itemHorizontalTranslationEnabled;
  @LabelVisibilityMode private int labelVisibilityMode;

  private BottomNavigationItemView[] buttons;
  private int selectedItemId = 0;
  private int selectedItemPosition = 0;

  private ColorStateList itemIconTint;
  @Dimension private int itemIconSize;
  private ColorStateList itemTextColorFromUser;
  private final ColorStateList itemTextColorDefault;
  @StyleRes private int itemTextAppearanceInactive;
  @StyleRes private int itemTextAppearanceActive;
  private Drawable itemBackground;
  private int itemBackgroundRes;
  private int[] tempChildWidths;
  @NonNull private SparseArray<BadgeDrawable> badgeDrawables = new SparseArray<>(ITEM_POOL_SIZE);

  private BottomNavigationPresenter presenter;
  private MenuBuilder menu;

  public BottomNavigationMenuView(Context context) {
    this(context, null);
  }

  public BottomNavigationMenuView(Context context, AttributeSet attrs) {
    super(context, attrs);
    final Resources res = getResources();
    inactiveItemMaxWidth =
        res.getDimensionPixelSize(R.dimen.design_bottom_navigation_item_max_width);
    inactiveItemMinWidth =
        res.getDimensionPixelSize(R.dimen.design_bottom_navigation_item_min_width);
    activeItemMaxWidth =
        res.getDimensionPixelSize(R.dimen.design_bottom_navigation_active_item_max_width);
    activeItemMinWidth =
        res.getDimensionPixelSize(R.dimen.design_bottom_navigation_active_item_min_width);
    itemHeight = res.getDimensionPixelSize(R.dimen.design_bottom_navigation_height);
    itemTextColorDefault = createDefaultColorStateList(android.R.attr.textColorSecondary);

    set = new AutoTransition();
    set.setOrdering(TransitionSet.ORDERING_TOGETHER);
    set.setDuration(ACTIVE_ANIMATION_DURATION_MS);
    set.setInterpolator(new FastOutSlowInInterpolator());
    set.addTransition(new TextScale());

    onClickListener =
        new OnClickListener() {
          @Override
          public void onClick(View v) {
            final BottomNavigationItemView itemView = (BottomNavigationItemView) v;
            MenuItem item = itemView.getItemData();
            if (!menu.performItemAction(item, presenter, 0)) {
              item.setChecked(true);
            }
          }
        };
    tempChildWidths = new int[BottomNavigationMenu.MAX_ITEM_COUNT];
  }

  @Override
  public void initialize(MenuBuilder menu) {
    this.menu = menu;
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    final int width = MeasureSpec.getSize(widthMeasureSpec);
    // Use visible item count to calculate widths
    final int visibleCount = menu.getVisibleItems().size();
    // Use total item counts to measure children
    final int totalCount = getChildCount();

    final int heightSpec = MeasureSpec.makeMeasureSpec(itemHeight, MeasureSpec.EXACTLY);

    if (isShifting(labelVisibilityMode, visibleCount) && itemHorizontalTranslationEnabled) {
      final View activeChild = getChildAt(selectedItemPosition);
      int activeItemWidth = activeItemMinWidth;
      if (activeChild.getVisibility() != View.GONE) {
        // Do an AT_MOST measure pass on the active child to get its desired width, and resize the
        // active child view based on that width
        activeChild.measure(
            MeasureSpec.makeMeasureSpec(activeItemMaxWidth, MeasureSpec.AT_MOST), heightSpec);
        activeItemWidth = Math.max(activeItemWidth, activeChild.getMeasuredWidth());
      }
      final int inactiveCount = visibleCount - (activeChild.getVisibility() != View.GONE ? 1 : 0);
      final int activeMaxAvailable = width - inactiveCount * inactiveItemMinWidth;
      final int activeWidth =
          Math.min(activeMaxAvailable, Math.min(activeItemWidth, activeItemMaxWidth));
      final int inactiveMaxAvailable =
          (width - activeWidth) / (inactiveCount == 0 ? 1 : inactiveCount);
      final int inactiveWidth = Math.min(inactiveMaxAvailable, inactiveItemMaxWidth);
      int extra = width - activeWidth - inactiveWidth * inactiveCount;

      for (int i = 0; i < totalCount; i++) {
        if (getChildAt(i).getVisibility() != View.GONE) {
          tempChildWidths[i] = (i == selectedItemPosition) ? activeWidth : inactiveWidth;
          // Account for integer division which sometimes leaves some extra pixel spaces.
          // e.g. If the nav was 10px wide, and 3 children were measured to be 3px-3px-3px, there
          // would be a 1px gap somewhere, which this fills in.
          if (extra > 0) {
            tempChildWidths[i]++;
            extra--;
          }
        } else {
          tempChildWidths[i] = 0;
        }
      }
    } else {
      final int maxAvailable = width / (visibleCount == 0 ? 1 : visibleCount);
      final int childWidth = Math.min(maxAvailable, activeItemMaxWidth);
      int extra = width - childWidth * visibleCount;
      for (int i = 0; i < totalCount; i++) {
        if (getChildAt(i).getVisibility() != View.GONE) {
          tempChildWidths[i] = childWidth;
          if (extra > 0) {
            tempChildWidths[i]++;
            extra--;
          }
        } else {
          tempChildWidths[i] = 0;
        }
      }
    }

    int totalWidth = 0;
    for (int i = 0; i < totalCount; i++) {
      final View child = getChildAt(i);
      if (child.getVisibility() == GONE) {
        continue;
      }
      child.measure(
          MeasureSpec.makeMeasureSpec(tempChildWidths[i], MeasureSpec.EXACTLY), heightSpec);
      ViewGroup.LayoutParams params = child.getLayoutParams();
      params.width = child.getMeasuredWidth();
      totalWidth += child.getMeasuredWidth();
    }
    setMeasuredDimension(
        View.resolveSizeAndState(
            totalWidth, MeasureSpec.makeMeasureSpec(totalWidth, MeasureSpec.EXACTLY), 0),
        View.resolveSizeAndState(itemHeight, heightSpec, 0));
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    final int count = getChildCount();
    final int width = right - left;
    final int height = bottom - top;
    int used = 0;
    for (int i = 0; i < count; i++) {
      final View child = getChildAt(i);
      if (child.getVisibility() == GONE) {
        continue;
      }
      if (ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL) {
        child.layout(width - used - child.getMeasuredWidth(), 0, width - used, height);
      } else {
        child.layout(used, 0, child.getMeasuredWidth() + used, height);
      }
      used += child.getMeasuredWidth();
    }
  }

  @Override
  public int getWindowAnimations() {
    return 0;
  }

  /**
   * Sets the tint which is applied to the menu items' icons.
   *
   * @param tint the tint to apply
   */
  public void setIconTintList(ColorStateList tint) {
    itemIconTint = tint;
    if (buttons != null) {
      for (BottomNavigationItemView item : buttons) {
        item.setIconTintList(tint);
      }
    }
  }

  /**
   * Returns the tint which is applied for the menu item labels.
   *
   * @return the ColorStateList that is used to tint menu items' icons
   */
  @Nullable
  public ColorStateList getIconTintList() {
    return itemIconTint;
  }

  /**
   * Sets the size to provide for the menu item icons.
   *
   * <p>For best image resolution, use an icon with the same size set in this method.
   *
   * @param iconSize the size to provide for the menu item icons in pixels
   */
  public void setItemIconSize(@Dimension int iconSize) {
    this.itemIconSize = iconSize;
    if (buttons != null) {
      for (BottomNavigationItemView item : buttons) {
        item.setIconSize(iconSize);
      }
    }
  }

  /** Returns the size in pixels provided for the menu item icons. */
  @Dimension
  public int getItemIconSize() {
    return itemIconSize;
  }

  /**
   * Sets the text color to be used for the menu item labels.
   *
   * @param color the ColorStateList used for menu item labels
   */
  public void setItemTextColor(ColorStateList color) {
    itemTextColorFromUser = color;
    if (buttons != null) {
      for (BottomNavigationItemView item : buttons) {
        item.setTextColor(color);
      }
    }
  }

  /**
   * Returns the text color used for menu item labels.
   *
   * @return the ColorStateList used for menu items labels
   */
  public ColorStateList getItemTextColor() {
    return itemTextColorFromUser;
  }

  /**
   * Sets the text appearance to be used for inactive menu item labels.
   *
   * @param textAppearanceRes the text appearance ID used for inactive menu item labels
   */
  public void setItemTextAppearanceInactive(@StyleRes int textAppearanceRes) {
    this.itemTextAppearanceInactive = textAppearanceRes;
    if (buttons != null) {
      for (BottomNavigationItemView item : buttons) {
        item.setTextAppearanceInactive(textAppearanceRes);
        // Set the text color if the user has set it, since itemTextColorFromUser takes precedence
        // over a color set in the text appearance.
        if (itemTextColorFromUser != null) {
          item.setTextColor(itemTextColorFromUser);
        }
      }
    }
  }

  /**
   * Returns the text appearance used for inactive menu item labels.
   *
   * @return the text appearance ID used for inactive menu item labels
   */
  @StyleRes
  public int getItemTextAppearanceInactive() {
    return itemTextAppearanceInactive;
  }

  /**
   * Sets the text appearance to be used for the active menu item label.
   *
   * @param textAppearanceRes the text appearance ID used for the active menu item label
   */
  public void setItemTextAppearanceActive(@StyleRes int textAppearanceRes) {
    this.itemTextAppearanceActive = textAppearanceRes;
    if (buttons != null) {
      for (BottomNavigationItemView item : buttons) {
        item.setTextAppearanceActive(textAppearanceRes);
        // Set the text color if the user has set it, since itemTextColorFromUser takes precedence
        // over a color set in the text appearance.
        if (itemTextColorFromUser != null) {
          item.setTextColor(itemTextColorFromUser);
        }
      }
    }
  }

  /**
   * Returns the text appearance used for the active menu item label.
   *
   * @return the text appearance ID used for the active menu item label
   */
  @StyleRes
  public int getItemTextAppearanceActive() {
    return itemTextAppearanceActive;
  }

  /**
   * Sets the resource ID to be used for item backgrounds.
   *
   * @param background the resource ID of the background
   */
  public void setItemBackgroundRes(int background) {
    itemBackgroundRes = background;
    if (buttons != null) {
      for (BottomNavigationItemView item : buttons) {
        item.setItemBackground(background);
      }
    }
  }

  /**
   * Returns the resource ID for the background of the menu items.
   *
   * @return the resource ID for the background
   * @deprecated Use {@link #getItemBackground()} instead.
   */
  @Deprecated
  public int getItemBackgroundRes() {
    return itemBackgroundRes;
  }

  /**
   * Sets the drawable to be used for item backgrounds.
   *
   * @param background the drawable of the background
   */
  public void setItemBackground(@Nullable Drawable background) {
    itemBackground = background;
    if (buttons != null) {
      for (BottomNavigationItemView item : buttons) {
        item.setItemBackground(background);
      }
    }
  }

  /**
   * Returns the drawable for the background of the menu items.
   *
   * @return the drawable for the background
   */
  @Nullable
  public Drawable getItemBackground() {
    if (buttons != null && buttons.length > 0) {
      // Return button background instead of itemBackground if possible, so that the correct
      // drawable is returned if the background is set via #setItemBackgroundRes.
      return buttons[0].getBackground();
    } else {
      return itemBackground;
    }
  }

  /**
   * Sets the navigation items' label visibility mode.
   *
   * <p>The label is either always shown, never shown, or only shown when activated. Also supports
   * "auto" mode, which uses the item count to determine whether to show or hide the label.
   *
   * @param labelVisibilityMode mode which decides whether or not the label should be shown. Can be
   *     one of {@link LabelVisibilityMode#LABEL_VISIBILITY_AUTO}, {@link
   *     LabelVisibilityMode#LABEL_VISIBILITY_SELECTED}, {@link
   *     LabelVisibilityMode#LABEL_VISIBILITY_LABELED}, or {@link
   *     LabelVisibilityMode#LABEL_VISIBILITY_UNLABELED}
   * @see #getLabelVisibilityMode()
   */
  public void setLabelVisibilityMode(@LabelVisibilityMode int labelVisibilityMode) {
    this.labelVisibilityMode = labelVisibilityMode;
  }

  /**
   * Returns the current label visibility mode.
   *
   * @see #setLabelVisibilityMode(int)
   */
  public int getLabelVisibilityMode() {
    return labelVisibilityMode;
  }

  /**
   * Sets whether the menu items horizontally translate on selection when the combined item widths
   * fill the screen.
   *
   * @param itemHorizontalTranslationEnabled whether the menu items horizontally translate on
   *     selection
   * @see #isItemHorizontalTranslationEnabled()
   */
  public void setItemHorizontalTranslationEnabled(boolean itemHorizontalTranslationEnabled) {
    this.itemHorizontalTranslationEnabled = itemHorizontalTranslationEnabled;
  }

  /**
   * Returns whether the menu items horizontally translate on selection when the combined item
   * widths fill the screen.
   *
   * @return whether the menu items horizontally translate on selection
   * @see #setItemHorizontalTranslationEnabled(boolean)
   */
  public boolean isItemHorizontalTranslationEnabled() {
    return itemHorizontalTranslationEnabled;
  }

  public ColorStateList createDefaultColorStateList(int baseColorThemeAttr) {
    final TypedValue value = new TypedValue();
    if (!getContext().getTheme().resolveAttribute(baseColorThemeAttr, value, true)) {
      return null;
    }
    ColorStateList baseColor = AppCompatResources.getColorStateList(getContext(), value.resourceId);
    if (!getContext()
        .getTheme()
        .resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true)) {
      return null;
    }
    int colorPrimary = value.data;
    int defaultColor = baseColor.getDefaultColor();
    return new ColorStateList(
        new int[][] {DISABLED_STATE_SET, CHECKED_STATE_SET, EMPTY_STATE_SET},
        new int[] {
          baseColor.getColorForState(DISABLED_STATE_SET, defaultColor), colorPrimary, defaultColor
        });
  }

  public void setPresenter(BottomNavigationPresenter presenter) {
    this.presenter = presenter;
  }

  public void buildMenuView() {
    removeAllViews();
    if (buttons != null) {
      for (BottomNavigationItemView item : buttons) {
        if (item != null) {
          itemPool.release(item);
          item.removeBadge();
        }
      }
    }

    if (menu.size() == 0) {
      selectedItemId = 0;
      selectedItemPosition = 0;
      buttons = null;
      return;
    }
    removeUnusedBadges();

    buttons = new BottomNavigationItemView[menu.size()];
    boolean shifting = isShifting(labelVisibilityMode, menu.getVisibleItems().size());
    for (int i = 0; i < menu.size(); i++) {
      presenter.setUpdateSuspended(true);
      menu.getItem(i).setCheckable(true);
      presenter.setUpdateSuspended(false);
      BottomNavigationItemView child = getNewItem();
      buttons[i] = child;
      child.setIconTintList(itemIconTint);
      child.setIconSize(itemIconSize);
      // Set the text color the default, then look for another text color in order of precedence.
      child.setTextColor(itemTextColorDefault);
      child.setTextAppearanceInactive(itemTextAppearanceInactive);
      child.setTextAppearanceActive(itemTextAppearanceActive);
      child.setTextColor(itemTextColorFromUser);
      if (itemBackground != null) {
        child.setItemBackground(itemBackground);
      } else {
        child.setItemBackground(itemBackgroundRes);
      }
      child.setShifting(shifting);
      child.setLabelVisibilityMode(labelVisibilityMode);
      child.initialize((MenuItemImpl) menu.getItem(i), 0);
      child.setItemPosition(i);
      child.setOnClickListener(onClickListener);
      if (selectedItemId != Menu.NONE && menu.getItem(i).getItemId() == selectedItemId) {
        selectedItemPosition = i;
      }
      setBadgeIfNeeded(child);
      addView(child);
    }
    selectedItemPosition = Math.min(menu.size() - 1, selectedItemPosition);
    menu.getItem(selectedItemPosition).setChecked(true);
  }

  public void updateMenuView() {
    if (menu == null || buttons == null) {
      return;
    }

    final int menuSize = menu.size();
    if (menuSize != buttons.length) {
      // The size has changed. Rebuild menu view from scratch.
      buildMenuView();
      return;
    }

    int previousSelectedId = selectedItemId;

    for (int i = 0; i < menuSize; i++) {
      MenuItem item = menu.getItem(i);
      if (item.isChecked()) {
        selectedItemId = item.getItemId();
        selectedItemPosition = i;
      }
    }
    if (previousSelectedId != selectedItemId) {
      // Note: this has to be called before BottomNavigationItemView#initialize().
      TransitionManager.beginDelayedTransition(this, set);
    }

    boolean shifting = isShifting(labelVisibilityMode, menu.getVisibleItems().size());
    for (int i = 0; i < menuSize; i++) {
      presenter.setUpdateSuspended(true);
      buttons[i].setLabelVisibilityMode(labelVisibilityMode);
      buttons[i].setShifting(shifting);
      buttons[i].initialize((MenuItemImpl) menu.getItem(i), 0);
      presenter.setUpdateSuspended(false);
    }
  }

  private BottomNavigationItemView getNewItem() {
    BottomNavigationItemView item = itemPool.acquire();
    if (item == null) {
      item = new BottomNavigationItemView(getContext());
    }
    return item;
  }

  public int getSelectedItemId() {
    return selectedItemId;
  }

  private boolean isShifting(@LabelVisibilityMode int labelVisibilityMode, int childCount) {
    return labelVisibilityMode == LabelVisibilityMode.LABEL_VISIBILITY_AUTO
        ? childCount > 3
        : labelVisibilityMode == LabelVisibilityMode.LABEL_VISIBILITY_SELECTED;
  }

  void tryRestoreSelectedItemId(int itemId) {
    final int size = menu.size();
    for (int i = 0; i < size; i++) {
      MenuItem item = menu.getItem(i);
      if (itemId == item.getItemId()) {
        selectedItemId = itemId;
        selectedItemPosition = i;
        item.setChecked(true);
        break;
      }
    }
  }

  SparseArray<BadgeDrawable> getBadgeDrawables() {
    return badgeDrawables;
  }

  void setBadgeDrawables(SparseArray<BadgeDrawable> badgeDrawables) {
    this.badgeDrawables = badgeDrawables;
  }

  @Nullable
  BadgeDrawable getBadge(int menuItemId) {
    return badgeDrawables.get(menuItemId);
  }

  /**
   * Initializes (if needed) and shows a {@link BadgeDrawable} associated with {@code menuItemId}.
   * Creates an instance of BadgeDrawable if none are associated with {@code menuItemId}. For
   * convenience, also returns the associated instance of BadgeDrawable.
   *
   * @param menuItemId Id of the menu item.
   * @return an instance of BadgeDrawable associated with {@code menuItemId}.
   */
  BadgeDrawable showBadge(int menuItemId) {
    validateMenuItemId(menuItemId);
    BadgeDrawable badgeDrawable = badgeDrawables.get(menuItemId);
    // Create an instance of BadgeDrawable if none were already initialized for this menu item.
    if (badgeDrawable == null) {
      badgeDrawable = BadgeDrawable.create(getContext());
      badgeDrawables.put(menuItemId, badgeDrawable);
    }
    badgeDrawable.setVisible(true);
    BottomNavigationItemView itemView = findItemView(menuItemId);
    if (itemView != null) {
      itemView.setBadge(badgeDrawable);
    }
    return badgeDrawable;
  }

  void removeBadge(int menuItemId) {
    validateMenuItemId(menuItemId);
    BadgeDrawable badgeDrawable = badgeDrawables.get(menuItemId);
    BottomNavigationItemView itemView = findItemView(menuItemId);
    if (itemView != null) {
      itemView.removeBadge();
    }
    if (badgeDrawable != null) {
      badgeDrawables.remove(menuItemId);
    }
  }

  private void setBadgeIfNeeded(BottomNavigationItemView child) {
    int childId = child.getId();
    if (!isValidId(childId)) {
      // Child doesn't have a valid id, do not set any BadgeDrawable on the view.
      return;
    }

    BadgeDrawable badgeDrawable = badgeDrawables.get(childId);
    if (badgeDrawable != null) {
      child.setBadge(badgeDrawable);
    }
  }

  private void removeUnusedBadges() {
    HashSet<Integer> activeKeys = new HashSet<>();
    // Remove keys from badgeDrawables that don't have a corresponding value in the menu.
    for (int i = 0; i < menu.size(); i++) {
      activeKeys.add(menu.getItem(i).getItemId());
    }

    for (int i = 0; i < badgeDrawables.size(); i++) {
      int key = badgeDrawables.keyAt(i);
      if (!activeKeys.contains(key)) {
        badgeDrawables.delete(key);
      }
    }
  }

  @Nullable
  private BottomNavigationItemView findItemView(int menuItemId) {
    validateMenuItemId(menuItemId);
    for (BottomNavigationItemView itemView : buttons) {
      if (itemView.getId() == menuItemId) {
        return itemView;
      }
    }
    return null;
  }

  private boolean isValidId(int viewId) {
    return viewId != View.NO_ID;
  }

  private void validateMenuItemId(int viewId) {
    if (!isValidId(viewId)) {
      throw new IllegalArgumentException(viewId + " is not a valid view id");
    }
  }
}

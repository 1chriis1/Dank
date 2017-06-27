package me.saket.dank.ui.submission;


import android.support.annotation.CheckResult;

import com.jakewharton.rxbinding2.internal.Notification;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;

import net.dean.jraw.models.CommentNode;
import net.dean.jraw.models.Submission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.reactivex.Observable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import me.saket.dank.utils.Commons;
import timber.log.Timber;

/**
 * Constructs comments to show in a submission. Ignores collapsed comments + adds reply fields + adds "load more" & "continue thread ->" items.
 */
public class CommentTreeConstructor {

  private Set<String> collapsedCommentNodeIds = new HashSet<>();      // Comments that are collapsed.
  private Set<String> loadingMoreCommentNodeIds = new HashSet<>();    // Comments for which more replies are being fetched.
  private Set<String> replyActiveForCommentNodeIds = new HashSet<>(); // Comments for which reply fields are active.
  private CommentNode rootCommentNode;
  private Relay<Object> changesRequiredStream = PublishRelay.create();

  public CommentTreeConstructor() {
  }

  /**
   * Set the root comment of a submission.
   */
  public void setComments(Submission submissionWithComments) {
    rootCommentNode = submissionWithComments.getComments();
    changesRequiredStream.accept(Notification.INSTANCE);
  }

  public void reset() {
    changesRequiredStream.accept(Notification.INSTANCE);
    rootCommentNode = null;
    collapsedCommentNodeIds.clear();
  }

  @CheckResult
  public Observable<List<SubmissionCommentRow>> streamUpdates() {
    return changesRequiredStream
        .observeOn(Schedulers.io())
        .map(o -> constructComments())
        .doOnNext(submissionCommentRows -> {
          for (SubmissionCommentRow submissionCommentRow : submissionCommentRows) {
            if (SubmissionCommentRow.Type.USER_COMMENT.equals(submissionCommentRow.type())) {
              CommentNode commentNode = ((DankCommentNode) submissionCommentRow).commentNode();
              if (commentNode.getParent() == commentNode || commentNode.getParent().equals(commentNode)) {
                Timber.w("CommentNode: %s", commentNode);
                throw new IllegalStateException("Infinite loop detected.");
              }
            }
          }
        })
        .map(Commons.toImmutable());
  }

  /**
   * Collapse/expand a comment.
   */
  public void toggleCollapse(CommentNode nodeToCollapse) {
    if (isCollapsed(nodeToCollapse)) {
      collapsedCommentNodeIds.remove(nodeToCollapse.getComment().getId());
    } else {
      collapsedCommentNodeIds.add(nodeToCollapse.getComment().getId());
    }
    changesRequiredStream.accept(Notification.INSTANCE);
  }

  public boolean isCollapsed(CommentNode commentNode) {
    return collapsedCommentNodeIds.contains(commentNode.getComment().getId());
  }

  /**
   * Enable "loading more…" progress indicator.
   */
  public Consumer<CommentNode> setMoreCommentsLoading(boolean loading) {
    return commentNode -> {
      if (loading) {
        loadingMoreCommentNodeIds.add(commentNode.getComment().getId());
      } else {
        loadingMoreCommentNodeIds.remove(commentNode.getComment().getId());
      }
      changesRequiredStream.accept(Notification.INSTANCE);
    };
  }

  public boolean areMoreCommentsLoadingFor(CommentNode commentNode) {
    return loadingMoreCommentNodeIds.contains(commentNode.getComment().getId());
  }

  /**
   * Show/hide reply field for a comment.
   */
  public void hideReply(CommentNode nodeToReply) {
    replyActiveForCommentNodeIds.remove(nodeToReply.getComment().getId());
    changesRequiredStream.accept(Notification.INSTANCE);
  }

  /**
   * Show/hide reply field for a comment.
   */
  public void showReplyAndExpandComments(CommentNode nodeToReply) {
    replyActiveForCommentNodeIds.add(nodeToReply.getComment().getId());
    collapsedCommentNodeIds.remove(nodeToReply.getComment().getId());
    changesRequiredStream.accept(Notification.INSTANCE);
  }

  public boolean isReplyActiveFor(CommentNode commentNode) {
    return replyActiveForCommentNodeIds.contains(commentNode.getComment().getId());
  }

  /**
   * Walk through the tree in pre-order, ignoring any collapsed comment tree node and flatten them in a single List.
   */
  private List<SubmissionCommentRow> constructComments() {
    if (rootCommentNode == null) {
      return Collections.emptyList();
    }
    //Timber.d("-----------------------------------------------");
    return constructComments(new ArrayList<>(rootCommentNode.getTotalSize()), rootCommentNode);
  }

  /**
   * Walk through the tree in pre-order, ignoring any collapsed comment tree node and flatten them in a single List.
   */
  private List<SubmissionCommentRow> constructComments(List<SubmissionCommentRow> flattenComments, CommentNode nextNode) {
    //String indentation = "";
    //if (nextNode.getDepth() != 0) {
    //  for (int step = 0; step < nextNode.getDepth(); step++) {
    //    indentation += "  ";
    //  }
    //}

    boolean isCommentNodeCollapsed = isCollapsed(nextNode);
    boolean isReplyActive = isReplyActiveFor(nextNode);

    if (nextNode.getDepth() != 0) {
      //Timber.i("%s(%s) %s: %s", indentation, nextNode.getComment().getId(), nextNode.getComment().getAuthor(), nextNode.getComment().getBody());
      flattenComments.add(DankCommentNode.create(nextNode, isCommentNodeCollapsed));
    }

    if (isReplyActive && !isCommentNodeCollapsed) {
      flattenComments.add(CommentReplyItem.create(nextNode));
    }

    if (nextNode.isEmpty() && !nextNode.hasMoreComments()) {
      return flattenComments;

    } else {
      // Ignore collapsed children.
      if (!isCommentNodeCollapsed) {
        List<CommentNode> childCommentsTree = nextNode.getChildren();
        for (CommentNode node : childCommentsTree) {
          constructComments(flattenComments, node);
        }

        if (nextNode.hasMoreComments()) {
          //Timber.d("%s(%s) %s has %d MORE ---------->",
          //    indentation, nextNode.getComment().getId(), nextNode.getComment().getAuthor(), nextNode.getMoreChildren().getCount()
          //);
          //Timber.d("%s %s", indentation, nextNode.getMoreChildren().getChildrenIds());
          flattenComments.add(LoadMoreCommentItem.create(nextNode, areMoreCommentsLoadingFor(nextNode)));
        }
      }
      return flattenComments;
    }
  }
}

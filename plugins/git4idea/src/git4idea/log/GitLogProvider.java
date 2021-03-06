/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.log;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.VcsKey;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcs.log.*;
import com.intellij.vcs.log.data.VcsLogBranchFilter;
import com.intellij.vcs.log.data.VcsLogUserFilter;
import com.intellij.vcs.log.impl.HashImpl;
import com.intellij.vcs.log.impl.VcsRefImpl;
import com.intellij.vcs.log.ui.filter.VcsLogTextFilter;
import git4idea.GitLocalBranch;
import git4idea.GitRemoteBranch;
import git4idea.GitVcs;
import git4idea.commands.GitCommand;
import git4idea.commands.GitSimpleHandler;
import git4idea.config.GitConfigUtil;
import git4idea.history.GitHistoryUtils;
import git4idea.history.GitLogParser;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryChangeListener;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Kirill Likhodedov
 */
public class GitLogProvider implements VcsLogProvider {

  private static final Logger LOG = Logger.getInstance(GitLogProvider.class);

  @NotNull private final Project myProject;
  @NotNull private final GitRepositoryManager myRepositoryManager;
  @NotNull private final VcsLogRefManager myRefSorter;
  @NotNull private final VcsLogObjectsFactory myVcsObjectsFactory;

  public GitLogProvider(@NotNull Project project, @NotNull GitRepositoryManager repositoryManager) {
    myProject = project;
    myRepositoryManager = repositoryManager;
    myRefSorter = new GitRefManager(myRepositoryManager);
    myVcsObjectsFactory = ServiceManager.getService(VcsLogObjectsFactory.class);
  }

  @NotNull
  @Override
  public List<? extends VcsFullCommitDetails> readFirstBlock(@NotNull VirtualFile root,
                                                             boolean ordered, int commitCount) throws VcsException {
    String[] params = { "HEAD", "--branches", "--remotes", "--tags", "--encoding=UTF-8", "--full-history", "--sparse",
                        "--max-count=" + commitCount};
    if (ordered) {
      params = ArrayUtil.append(params, "--date-order");
    }
    return GitHistoryUtils.history(myProject, root, params);
  }

  @NotNull
  @Override
  public List<TimedVcsCommit> readAllHashes(@NotNull VirtualFile root) throws VcsException {
    return GitHistoryUtils.readAllHashes(myProject, root);
  }

  @NotNull
  @Override
  public List<? extends VcsShortCommitDetails> readShortDetails(@NotNull VirtualFile root, @NotNull List<String> hashes) throws VcsException {
    return GitHistoryUtils.readMiniDetails(myProject, root, hashes);
  }

  @NotNull
  @Override
  public List<? extends VcsFullCommitDetails> readFullDetails(@NotNull VirtualFile root, @NotNull List<String> hashes) throws VcsException {
    return GitHistoryUtils.commitsDetails(myProject, root, hashes);
  }

  @NotNull
  @Override
  public Collection<VcsRef> readAllRefs(@NotNull VirtualFile root) throws VcsException {
    myRepositoryManager.waitUntilInitialized();
    GitRepository repository = myRepositoryManager.getRepositoryForRoot(root);
    if (repository == null) {
      LOG.error("Repository not found for root " + root);
      return Collections.emptyList();
    }

    repository.update();
    Collection<GitLocalBranch> localBranches = repository.getBranches().getLocalBranches();
    Collection<GitRemoteBranch> remoteBranches = repository.getBranches().getRemoteBranches();
    Collection<VcsRef> refs = new ArrayList<VcsRef>(localBranches.size() + remoteBranches.size());
    for (GitLocalBranch localBranch : localBranches) {
      refs.add(new VcsRefImpl(HashImpl.build(localBranch.getHash()), localBranch.getName(), GitRefManager.LOCAL_BRANCH, root));
    }
    for (GitRemoteBranch remoteBranch : remoteBranches) {
      refs.add(new VcsRefImpl(HashImpl.build(remoteBranch.getHash()), remoteBranch.getNameForLocalOperations(),
                          GitRefManager.REMOTE_BRANCH, root));
    }
    String currentRevision = repository.getCurrentRevision();
    if (currentRevision != null) { // null => fresh repository
      refs.add(new VcsRefImpl(HashImpl.build(currentRevision), "HEAD", GitRefManager.HEAD, root));
    }

    refs.addAll(readTags(root));
    return refs;
  }

  // TODO this is to be removed when tags will be supported by the GitRepositoryReader
  private Collection<? extends VcsRef> readTags(@NotNull VirtualFile root) throws VcsException {
    GitSimpleHandler tagHandler = new GitSimpleHandler(myProject, root, GitCommand.LOG);
    tagHandler.setSilent(true);
    tagHandler.addParameters("--tags", "--no-walk", "--format=%H%d" + GitLogParser.RECORD_START_GIT, "--decorate=full");
    String out = tagHandler.run();
    Collection<VcsRef> refs = new ArrayList<VcsRef>();
    for (String record : out.split(GitLogParser.RECORD_START)) {
      if (!StringUtil.isEmptyOrSpaces(record)) {
        refs.addAll(RefParser.parseCommitRefs(record.trim(), root));
      }
    }
    return refs;
  }

  @NotNull
  @Override
  public VcsKey getSupportedVcs() {
    return GitVcs.getKey();
  }

  @NotNull
  @Override
  public VcsLogRefManager getReferenceManager() {
    return myRefSorter;
  }

  @Override
  public void subscribeToRootRefreshEvents(@NotNull final Collection<VirtualFile> roots, @NotNull final VcsLogRefresher refresher) {
    myProject.getMessageBus().connect(myProject).subscribe(GitRepository.GIT_REPO_CHANGE, new GitRepositoryChangeListener() {
      @Override
      public void repositoryChanged(@NotNull GitRepository repository) {
        VirtualFile root = repository.getRoot();
        if (roots.contains(root)) {
          refresher.refresh(root);
        }
      }
    });
  }

  @NotNull
  @Override
  public List<? extends VcsFullCommitDetails> getFilteredDetails(@NotNull final VirtualFile root,
                                                                 @NotNull Collection<VcsLogFilter> filters) throws VcsException {
    List<String> filterParameters = ContainerUtil.newArrayList();

    List<VcsLogBranchFilter> branchFilters = ContainerUtil.findAll(filters, VcsLogBranchFilter.class);
    if (!branchFilters.isEmpty()) {
      String branchFilter = joinFilters(branchFilters, new Function<VcsLogBranchFilter, String>() {
        @Override
        public String fun(VcsLogBranchFilter filter) {
          return filter.getBranchName();
        }
      });
      filterParameters.add(prepareParameter("branches", branchFilter));
    }
    else {
      filterParameters.add("--all");
    }

    List<VcsLogUserFilter> userFilters = ContainerUtil.findAll(filters, VcsLogUserFilter.class);
    if (!userFilters.isEmpty()) {
      String authorFilter = joinFilters(userFilters, new Function<VcsLogUserFilter, String>() {
        @Override
        public String fun(VcsLogUserFilter filter) {
          return filter.getUserName(root);
        }
      });
      filterParameters.add(prepareParameter("author", authorFilter));
    }

    List<VcsLogTextFilter> textFilters = ContainerUtil.findAll(filters, VcsLogTextFilter.class);
    if (textFilters.size() > 1) {
      LOG.warn("Expected only one text filter: " + textFilters);
    }
    else if (!textFilters.isEmpty()) {
      String textFilter = textFilters.iterator().next().getText();
      filterParameters.add(prepareParameter("grep", textFilter));
    }

    filterParameters.add("--regexp-ignore-case"); // affects case sensitivity of any filter
    return GitHistoryUtils.getAllDetails(myProject, root, filterParameters);
  }

  @Nullable
  @Override
  public VcsUser getCurrentUser(@NotNull VirtualFile root) throws VcsException {
    String userName = GitConfigUtil.getValue(myProject, root, GitConfigUtil.USER_NAME);
    return userName == null ? null : myVcsObjectsFactory.createUser(userName);
  }

  private static String prepareParameter(String paramName, String value) {
    return "--" + paramName + "=" + value; // no value escaping needed, because the parameter itself will be quoted by GeneralCommandLine
  }

  private static <T> String joinFilters(List<T> filters, Function<T, String> toString) {
    return StringUtil.join(filters, toString, "\\|");
  }
}
// Reminds, then unassigns, contributors who have gone quiet on an assigned issue, and reminds,
// then closes, pull requests whose authors have gone quiet after a review asked for changes.
// Run daily by .github/workflows/inactive-assignees.yml through actions/github-script.

const REMIND_AFTER_DAYS = 10;
const UNASSIGN_AFTER_DAYS = 14;
const REMINDER_MARKER = '<!-- flipjack:inactivity-reminder -->';

// A pull request is reminded 7 quiet days after changes were requested, and closed 7 days after
// the reminder if its author still hasn't pushed or commented: two weeks in all.
const PR_REMIND_AFTER_DAYS = 7;
const PR_CLOSE_AFTER_REMINDER_DAYS = 7;
const PR_REMINDER_MARKER = '<!-- flipjack:stale-pr-reminder -->';

const DAY_MS = 24 * 60 * 60 * 1000;

const LINKED_PULL_REQUESTS = `
  query($owner: String!, $repo: String!, $number: Int!) {
    repository(owner: $owner, name: $repo) {
      issue(number: $number) {
        timelineItems(itemTypes: [CROSS_REFERENCED_EVENT, CONNECTED_EVENT], last: 50) {
          nodes {
            ... on CrossReferencedEvent { source { ... on PullRequest { state author { login } } } }
            ... on ConnectedEvent { subject { ... on PullRequest { state author { login } } } }
          }
        }
      }
    }
  }`;

const PULL_REQUEST_ACTIVITY = `
  query($owner: String!, $repo: String!, $number: Int!) {
    repository(owner: $owner, name: $repo) {
      pullRequest(number: $number) {
        timelineItems(itemTypes: [PULL_REQUEST_COMMIT, HEAD_REF_FORCE_PUSHED_EVENT, ISSUE_COMMENT,
                                  PULL_REQUEST_REVIEW], last: 100) {
          nodes {
            __typename
            ... on PullRequestCommit { commit { committedDate } }
            ... on HeadRefForcePushedEvent { createdAt actor { login } }
            ... on IssueComment { createdAt body author { __typename login } }
            ... on PullRequestReview { submittedAt state author { login } }
          }
        }
      }
    }
  }`;

module.exports = async ({ github, context, core }) => {
  const { owner, repo } = context.repo;
  const now = Date.now();
  const maintainers = new Map();

  async function isMaintainer(login) {
    if (!maintainers.has(login)) {
      let result = false;
      try {
        const { data } = await github.rest.repos.getCollaboratorPermissionLevel({ owner, repo, username: login });
        result = data.permission === 'admin' || data.permission === 'write' || data.role_name === 'maintain';
      } catch {
        // not a collaborator
      }
      maintainers.set(login, result);
    }
    return maintainers.get(login);
  }

  // Pull requests first: closing a stale one here lets the issue it holds be released below.
  await closeStalePullRequests();
  await releaseInactiveAssignees();

  async function closeStalePullRequests() {
    const pulls = await github.paginate(github.rest.pulls.list, { owner, repo, state: 'open', per_page: 100 });

    for (const pull of pulls) {
      const author = pull.user.login;
      if (pull.user.type === 'Bot' || await isMaintainer(author)) {
        continue;
      }
      const number = pull.number;
      const result = await github.graphql(PULL_REQUEST_ACTIVITY, { owner, repo, number });
      const items = result.repository.pullRequest.timelineItems.nodes;

      // waiting on the author only while a maintainer's latest verdict asks for changes
      const verdicts = [];
      for (const item of items) {
        if (item.__typename === 'PullRequestReview' && item.author && item.author.login !== author
            && ['CHANGES_REQUESTED', 'APPROVED', 'DISMISSED'].includes(item.state)
            && await isMaintainer(item.author.login)) {
          verdicts.push(item);
        }
      }
      const verdict = verdicts.pop();
      if (!verdict || verdict.state !== 'CHANGES_REQUESTED') {
        continue;
      }
      const requested = Date.parse(verdict.submittedAt);

      // the author's latest push, comment or review reply
      const authorActivity = Math.max(0, ...items.map((item) => {
        switch (item.__typename) {
          case 'PullRequestCommit': return Date.parse(item.commit.committedDate);
          case 'HeadRefForcePushedEvent': return item.actor && item.actor.login === author ? Date.parse(item.createdAt) : 0;
          case 'IssueComment': return item.author && item.author.login === author ? Date.parse(item.createdAt) : 0;
          case 'PullRequestReview': return item.author && item.author.login === author ? Date.parse(item.submittedAt) : 0;
          default: return 0;
        }
      }));
      if (authorActivity > requested) {
        continue; // the author responded; it's the maintainers' turn
      }

      let reminded = 0;
      for (const item of items) {
        if (item.__typename === 'IssueComment' && item.body.includes(PR_REMINDER_MARKER)
            && Date.parse(item.createdAt) > requested && item.author
            && (item.author.__typename === 'Bot' || await isMaintainer(item.author.login))) {
          reminded = Math.max(reminded, Date.parse(item.createdAt));
        }
      }

      if (reminded && (now - reminded) / DAY_MS >= PR_CLOSE_AFTER_REMINDER_DAYS) {
        await github.rest.issues.createComment({ owner, repo, issue_number: number,
          body: `@${author} there's been no update since changes were requested, so this pull request `
            + `has been closed, and any issue it was holding is released for someone else. Thanks for `
            + `your work! If you'd like to pick it up again, push your changes and ask for it to be `
            + `reopened, or open a new pull request.` });
        await github.rest.pulls.update({ owner, repo, pull_number: number, state: 'closed' });
        core.info(`PR #${number}: closed after ${Math.floor((now - requested) / DAY_MS)} quiet days`);
      } else if (!reminded && (now - requested) / DAY_MS >= PR_REMIND_AFTER_DAYS) {
        await github.rest.issues.createComment({ owner, repo, issue_number: number,
          body: `${PR_REMINDER_MARKER}\n@${author} just checking in: are you still working on this? `
            + `If there's no push or comment in the next ${PR_CLOSE_AFTER_REMINDER_DAYS} days, this pull `
            + `request will be closed and any issue it holds released for someone else. If you can't `
            + `continue, that's fine; just say so here.` });
        core.info(`PR #${number}: reminded @${author} after ${Math.floor((now - requested) / DAY_MS)} quiet days`);
      }
    }
  }

  async function hasOpenPullRequest(number, login) {
    const result = await github.graphql(LINKED_PULL_REQUESTS, { owner, repo, number });
    return result.repository.issue.timelineItems.nodes.some((node) => {
      const pr = node.source || node.subject;
      return pr && pr.state === 'OPEN' && pr.author && pr.author.login === login;
    });
  }

  async function releaseInactiveAssignees() {
    const issues = await github.paginate(github.rest.issues.listForRepo,
      { owner, repo, state: 'open', assignee: '*', per_page: 100 });

    for (const issue of issues) {
      if (issue.pull_request) {
        continue;
      }
      const number = issue.number;
      const [events, comments] = await Promise.all([
        github.paginate(github.rest.issues.listEvents, { owner, repo, issue_number: number, per_page: 100 }),
        github.paginate(github.rest.issues.listComments, { owner, repo, issue_number: number, per_page: 100 }),
      ]);

      for (const { login } of issue.assignees) {
        if (await isMaintainer(login) || await hasOpenPullRequest(number, login)) {
          continue;
        }

        // activity: when they were assigned, or their latest comment, whichever is later
        const assigned = events.filter((e) => e.event === 'assigned' && e.assignee && e.assignee.login === login).pop();
        const lastComment = comments.filter((c) => c.user.login === login).pop();
        const lastActivity = Math.max(
          Date.parse(assigned ? assigned.created_at : issue.created_at),
          lastComment ? Date.parse(lastComment.created_at) : 0);
        const idleDays = (now - lastActivity) / DAY_MS;
        const reminded = comments.some((c) => c.body.includes(REMINDER_MARKER) && Date.parse(c.created_at) > lastActivity);

        if (idleDays >= UNASSIGN_AFTER_DAYS) {
          await github.rest.issues.removeAssignees({ owner, repo, issue_number: number, assignees: [login] });
          await github.rest.issues.createComment({ owner, repo, issue_number: number,
            body: `@${login} there's been no activity here for ${UNASSIGN_AFTER_DAYS} days, so this issue `
              + `has been released for someone else. Thanks for your interest! If you'd still like to `
              + `work on it, comment \`/assign\` again while it's free.` });
          core.info(`#${number}: unassigned @${login} after ${Math.floor(idleDays)} idle days`);
        } else if (idleDays >= REMIND_AFTER_DAYS && !reminded) {
          await github.rest.issues.createComment({ owner, repo, issue_number: number,
            body: `${REMINDER_MARKER}\n@${login} just checking in: how's this going? If there's no `
              + `activity in the next ${UNASSIGN_AFTER_DAYS - REMIND_AFTER_DAYS} days, the issue will be `
              + `released for someone else. A comment here or an open pull request keeps it yours, `
              + `and \`/unassign\` releases it now.` });
          core.info(`#${number}: reminded @${login} after ${Math.floor(idleDays)} idle days`);
        }
      }
    }
  }
};


-----
# <a name="skein.spools.chime">skein.spools.chime</a>


Human-attention notification bridge for Skein graph events.

  Chime watches strand mutations, evaluates small userland rules, and sends
  attention notices through a workspace-bound local notifier command. It owns
  only weaver-lifetime runtime state and composes the public weaver/event API.




## <a name="skein.spools.chime/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous notifier worker threads.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L77-L79">Source</a></sub></p>

## <a name="skein.spools.chime/contribute">`contribute`</a>
``` clojure
(contribute {:keys [runtime]})
```
Function.

Materialize Chime's rule kind for dependent module contributions.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L373-L377">Source</a></sub></p>

## <a name="skein.spools.chime/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install chime's mutation barrier and event handler into the active weaver.

  Chime ships no rules and no notifier: trusted config supplies rules with
  `register!` and a notifier with `set-notifier!`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L404-L422">Source</a></sub></p>

## <a name="skein.spools.chime/mutation-registration-barrier!">`mutation-registration-barrier!`</a>
``` clojure
(mutation-registration-barrier! _context)
```
Function.

Serialize a pending graph mutation after any in-progress rule registration.

  Installed as a synchronous pre-commit hook. Its return value is ignored.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L365-L371">Source</a></sub></p>

## <a name="skein.spools.chime/notifier">`notifier`</a>
``` clojure
(notifier)
```
Function.

Return the current notifier binding, or nil when none is bound.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L137-L140">Source</a></sub></p>

## <a name="skein.spools.chime/notify!">`notify!`</a>
``` clojure
(notify! notification)
```
Function.

Send one notification through the current binding.

  Returns an inspectable map immediately. Missing notifier is recorded as a loud
  failure instead of silently dropping the notification.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L179-L195">Source</a></sub></p>

## <a name="skein.spools.chime/on-event">`on-event`</a>
``` clojure
(on-event event)
```
Function.

Weaver event handler: scan graph changes for attention notifications.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L360-L363">Source</a></sub></p>

## <a name="skein.spools.chime/recent-failures">`recent-failures`</a>
``` clojure
(recent-failures)
```
Function.

Return the last 100 notifier, process, and rule failures for this weaver lifetime.

  Entries diverge from the blessed event-failure entry
  (`skein.api.events.alpha/recent-failures`) on two keys, because chime's
  failures carry no event context to describe them with:

  - `:kind` — `:notifier-missing`, `:process`, or `:rule`. The blessed entry has
    no counterpart; it discriminates on `:event/type`, which chime's failures do
    not have. Two of chime's three kinds are not throws at all, so the kind is
    the only thing that says what went wrong.
  - `:message` — present only when something threw, not `:exception/message`:
    a missing notifier and a non-zero notifier exit are failures without an
    exception to take a message from.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L94-L109">Source</a></sub></p>

## <a name="skein.spools.chime/reconcile">`reconcile`</a>
``` clojure
(reconcile {:keys [runtime]})
```
Function.

Baseline changed effective rules, then make the complete view visible.

  Publication has already validated every owner partition. This second phase is
  deliberately serialized with scans and mutation pre-commit hooks: a changed
  or restored rule receives its baseline before the event lane can observe it.
  Removed rules lose their seen entries at the same time (MI2/MI3).
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L379-L402">Source</a></sub></p>

## <a name="skein.spools.chime/register!">`register!`</a>
``` clojure
(register! name fn-symbol)
```
Function.

Register or replace a notification rule.

  `fn-symbol` names a function receiving `{:event .. :strand ..}` and returning
  nil or `{:title .. :body ..}`. Currently matching strands become the rule's
  initial seen baseline, so durable conditions do not notify after registration
  even when they have never notified before. Mutations serialized after
  registration notify normally.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L213-L240">Source</a></sub></p>

## <a name="skein.spools.chime/reset-seen!">`reset-seen!`</a>
``` clojure
(reset-seen!)
```
Function.

Clear per-weaver notification deduplication and batch-scan state.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L111-L116">Source</a></sub></p>

## <a name="skein.spools.chime/rules">`rules`</a>
``` clojure
(rules)
```
Function.

Return registered notification rules ordered by key.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L242-L245">Source</a></sub></p>

## <a name="skein.spools.chime/scan!">`scan!`</a>
``` clojure
(scan! event)
(scan!)
```
Function.

Evaluate registered rules against currently affected strands.

  Rules receive `{:event .. :strand .. :ready-ids #{..}}`; `:ready-ids` is
  computed once per scan. Batch events and their per-strand fanout share a
  `:batch/id`, and only the first event of a batch triggers a scan.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L339-L358">Source</a></sub></p>

## <a name="skein.spools.chime/set-notifier!">`set-notifier!`</a>
``` clojure
(set-notifier! notifier)
```
Function.

Bind the local notifier command for this weaver lifetime.

  The binding is `{:argv [..]}`. Chime appends the notification title as the
  final argument and writes the body to stdin. Rebinding replaces the prior
  value; pass a valid binding after every weaver startup or config reload.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L127-L135">Source</a></sub></p>

## <a name="skein.spools.chime/unregister!">`unregister!`</a>
``` clojure
(unregister! name)
```
Function.

Unregister a notification rule by key.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L247-L268">Source</a></sub></p>

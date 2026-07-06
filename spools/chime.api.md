# Table of contents
-  [`skein.spools.chime`](#skein.spools.chime)  - Human-attention notification bridge for Skein graph events.
    -  [`*runtime*`](#skein.spools.chime/*runtime*) - Runtime captured for asynchronous notifier worker threads.
    -  [`defrule!`](#skein.spools.chime/defrule!) - Register or replace a notification rule.
    -  [`failures`](#skein.spools.chime/failures) - Return recorded notifier, process, and rule failures for this weaver lifetime.
    -  [`install!`](#skein.spools.chime/install!) - Install chime's event handler into the active weaver.
    -  [`notifier`](#skein.spools.chime/notifier) - Return the current notifier binding, or nil when none is bound.
    -  [`notify!`](#skein.spools.chime/notify!) - Send one notification through the current binding.
    -  [`on-event`](#skein.spools.chime/on-event) - Weaver event handler: scan graph changes for attention notifications.
    -  [`remove-rule!`](#skein.spools.chime/remove-rule!) - Remove a registered notification rule by name.
    -  [`reset-seen!`](#skein.spools.chime/reset-seen!) - Clear per-weaver notification deduplication and batch-scan state.
    -  [`rules`](#skein.spools.chime/rules) - Return registered notification rules ordered by name.
    -  [`scan!`](#skein.spools.chime/scan!) - Evaluate registered rules against currently affected strands.
    -  [`set-notifier!`](#skein.spools.chime/set-notifier!) - Bind the local notifier command for this weaver lifetime.

-----
# <a name="skein.spools.chime">skein.spools.chime</a>


Human-attention notification bridge for Skein graph events.

  Chime watches strand mutations, evaluates small userland rules, and sends
  attention notices through a workspace-bound local notifier command. It owns
  only weaver-lifetime runtime state and composes the public weaver/event API.




## <a name="skein.spools.chime/*runtime*">`*runtime*`</a>




Runtime captured for asynchronous notifier worker threads.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L44-L46">Source</a></sub></p>

## <a name="skein.spools.chime/defrule!">`defrule!`</a>
``` clojure
(defrule! name fn-symbol)
```
Function.

Register or replace a notification rule.

  `fn-symbol` names a function receiving `{:event .. :strand ..}` and returning
  nil or `{:title .. :body ..}`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L168-L177">Source</a></sub></p>

## <a name="skein.spools.chime/failures">`failures`</a>
``` clojure
(failures)
```
Function.

Return recorded notifier, process, and rule failures for this weaver lifetime.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L61-L64">Source</a></sub></p>

## <a name="skein.spools.chime/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install chime's event handler into the active weaver.

  Chime ships no rules and no notifier: trusted config supplies rules with
  `defrule!` and a notifier with `set-notifier!`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L267-L281">Source</a></sub></p>

## <a name="skein.spools.chime/notifier">`notifier`</a>
``` clojure
(notifier)
```
Function.

Return the current notifier binding, or nil when none is bound.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L92-L95">Source</a></sub></p>

## <a name="skein.spools.chime/notify!">`notify!`</a>
``` clojure
(notify! notification)
```
Function.

Send one notification through the current binding.

  Returns an inspectable map immediately. Missing notifier is recorded as a loud
  failure instead of silently dropping the notification.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L134-L150">Source</a></sub></p>

## <a name="skein.spools.chime/on-event">`on-event`</a>
``` clojure
(on-event event)
```
Function.

Weaver event handler: scan graph changes for attention notifications.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L262-L265">Source</a></sub></p>

## <a name="skein.spools.chime/remove-rule!">`remove-rule!`</a>
``` clojure
(remove-rule! name)
```
Function.

Remove a registered notification rule by name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L184-L191">Source</a></sub></p>

## <a name="skein.spools.chime/reset-seen!">`reset-seen!`</a>
``` clojure
(reset-seen!)
```
Function.

Clear per-weaver notification deduplication and batch-scan state.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L66-L71">Source</a></sub></p>

## <a name="skein.spools.chime/rules">`rules`</a>
``` clojure
(rules)
```
Function.

Return registered notification rules ordered by name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L179-L182">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L243-L260">Source</a></sub></p>

## <a name="skein.spools.chime/set-notifier!">`set-notifier!`</a>
``` clojure
(set-notifier! binding)
```
Function.

Bind the local notifier command for this weaver lifetime.

  The binding is `{:argv [..]}`. Chime appends the notification title as the
  final argument and writes the body to stdin. Rebinding replaces the prior
  value; pass a valid binding after every weaver startup or config reload.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/chime/src/skein/spools/chime.clj#L82-L90">Source</a></sub></p>

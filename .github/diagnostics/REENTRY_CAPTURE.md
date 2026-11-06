# Bounded foreground capture

Run 42203584629 (app 5ac15b5eeb26296d69008a39e7ff1b4e23d4f8c5) failed because the first 5-second re-entry capture still displayed the Kilowallet launch transition. The next two frames displayed complete Lightning text. The app process stayed at PID 30099. Create and all five native markers passed. The failed run is retained as failure; it is not release approval.

The revised diagnostic retains every transition capture and requires both Lightning and EXPORT SCB within 30 seconds (including OCR time), before capturing three further independent frames. Timeout, missing text, changed process, or failed native checks still fail qualification. No application source, wallet storage, native engine, or fund-handling changes are made.

Run the offline audit with evidence directory, exact expected run ID, and independently verified orchestration SHA. A successful automated audit still requires manual image review and does not authorize signing by itself.

Run42218335361 failed before re-entry because Settings was still a blank launch frame, with the return-to-Kilowallet status link. LND native checks passed. Settings now gets its own bounded60-second readiness check with every transition retained; the app re-entry deadline remains30seconds. ContentView.swift shows the earlier black Kilowallet frame is its inactive-scene privacy cover, not evidence of a process restart. Neither failed run is approved.

(ns lawfirm.governor
  "LawFirmGovernor — the independent gate every proposal passes before it can
  touch the record (itonami actor pattern, ADR-2607011000).

  The single invariant the whole repo exists to hold:

    > **the practice never performs a write, disclosure, filing, payment or
    > authentication the governor refuses.**

  The governor is deliberately *not* the advisor's reviewer. It never reads
  the advisor's reasoning and never weighs how good the draft is. It checks
  the proposal against the registered record — 名簿, 委任契約, 利益相反,
  受任範囲, 預り金残高, 期限 — and holds when the record does not support it.
  A brilliant proposal on a matter whose 控訴期間 lapsed last week is held for
  the same reason a poor one is.

  ## HARD invariants (`:hard? true` → always `:hold`, never overridable)

    1. :no-actuation                     — `:effect` must be `:propose`. The
                                           actor never files, pays, sends or
                                           signs; it prepares and records.
    2. counsel verification              — the acting person is on the
                                           弁護士名簿, active, verified
                                           recently (弁護士法72条).
                                           `lawfirm.partner`.
    3. :no-client / :unknown-matter /
       :matter-wrong-client              — provenance.
    4. :conflict-check-not-cleared       — a *human* clearance record exists,
                                           signed and dated (規程27・28条).
    5. :conflict-hit-live                — and a re-run screen is still clean
                                           today. A clearance cannot outlive
                                           the facts it was made on.
    6. :fee-agreement-missing            — 委任契約書 before billable work
                                           (規程30条).
    7. :engagement-scope-exceeded        — recorded + proposed hours must stay
                                           inside the registered 受任範囲.
                                           Billing past it is scope creep, not
                                           diligence.
    8. trust rules                       — 分別管理, no overdraft, no
                                           appropriation without an issued
                                           invoice. `lawfirm.trust`.
    9. :referral-fee-forbidden and the
       rest of `lawfirm.partner/grant-violations`
                                         — 規程13条, both directions.
   10. :work-product-not-reviewed        — nothing leaves the practice that a
                                           verified 弁護士 has not personally
                                           examined (法務省 2023 ガイドライン:
                                           弁護士が自ら精査し、必要に応じ自ら
                                           修正する). This is the line between
                                           a governed drafting tool and
                                           non-lawyer practice.
   11. :reviewer-not-counsel             — and the reviewer must be that
                                           verified 弁護士, not the requester.
   12. :critical-deadline-breached       — no ordinary work is committed on a
                                           matter with a lapsed critical
                                           期限 until the lapse is addressed.
                                           Remediation ops are exempt; silence
                                           is not.
   13. :privilege-boundary               — matter content reaches a co-counsel
                                           only through an unexpired grant
                                           carrying the capability
                                           (秘密保持義務).
   14. `lawfirm.transmission/outbound-violations`
                                         — 送達. The destination must be a
                                           registered, currently-verified
                                           recipient on this matter, the
                                           document must already be 発出済,
                                           and the channel must not be one
                                           the document cannot lawfully take.
                                           誤送信 is the most common
                                           confidentiality incident in
                                           practice and it has one structural
                                           cause: a destination typed at send
                                           time.
   15. :recipient-verifier-not-counsel   — a 送達先 is registered on a
                                           弁護士's verification. An
                                           unverified destination is the same
                                           hazard as an unverified counsel.
   16. `lawfirm.qa/send-violations`      — a 相談回答 leaves only if a
                                           verified 弁護士 personally examined
                                           it, and a pre-engagement question
                                           carries its own conflict screen
                                           (the matter-scoped ones say nothing
                                           about a questioner with no matter).
   17. :qa-reviewer-not-counsel          — and only a verified 弁護士 may
                                           record that examination.
   18. :prose-in-record                  — an intake, an arriving fax and a
                                           question carry a digest, never the
                                           text. `lawfirm.intake` explains
                                           why; this invariant is what stops
                                           it from being a convention that
                                           erodes.
   19. `lawfirm.transmission/confirmation-violations`
                                         — a 送達 outcome may only be recorded
                                           against an outbound 送達 that
                                           exists. Notably NOT here: a
                                           mismatch between the number a
                                           transport dialled and the
                                           registered one. The document has
                                           already left; refusing to write
                                           down where it went would destroy
                                           the evidence of the incident.

  ## Escalation (`:escalate? true` → human 弁護士 sign-off, regardless of
  confidence)

  Everything that changes the practice's obligations to a client, a court or
  a counterparty. These are escalated because they are *decisions*, not
  because the model is unsure: 受任, 提出, 出金, 和解, 辞任, 書面の外部送付,
  送達, 相談回答の送信, 共同受任の招請. Plus any proposal below
  `confidence-floor`.

  Recording something that *arrived* is not on that list. An inbound fax and a
  client's question are facts about the world, and a practice that needs
  sign-off before it may write down what it received will simply not write it
  down."
  (:require [governor.core :as gov]
            [lawfirm.conflict :as conflict]
            [lawfirm.deadline :as deadline]
            [lawfirm.partner :as partner]
            [lawfirm.qa :as qa]
            [lawfirm.store :as store]
            [lawfirm.transmission :as transmission]
            [lawfirm.trust :as trust]))

(def confidence-floor 0.6)

(def always-escalate-ops
  "Ops that are a 弁護士's decision by their nature. Escalated at any
  confidence — an advisor being *sure* the practice should settle is not a
  reason to let it settle."
  #{:accept-representation
    :file-with-court
    :disburse-trust
    :settle
    :withdraw-representation
    :issue-work-product
    :transmit-work-product
    :send-qa-answer
    :invite-partner-counsel})

(def ^:private matter-scoped-ops
  "Ops that must cite a registered matter belonging to the requesting client.

  `:send-qa-answer` and `:record-qa-question` are deliberately absent: a
  法律相談 arrives before there is a matter, and requiring one here would
  either block the front door or push practices into opening a matter for
  every enquiry — which is itself a 利益相反 hazard. `lawfirm.qa` carries the
  screen those questions need instead."
  #{:run-conflict-check :record-time-entry :prepare-work-product
    :review-work-product :issue-work-product :transmit-work-product
    :register-recipient :receive-trust :disburse-trust
    :issue-invoice :accept-representation :file-with-court :settle
    :withdraw-representation :invite-partner-counsel :remediate-deadline})

(def ^:private billable-ops
  "Ops that consume the registered engagement scope. Drafting an answer to a
  client's question is billable work on the matter for the same reason
  drafting a 書面 is; leaving it out would make 'answer by email' the way to
  do unmetered work."
  #{:record-time-entry :prepare-work-product :draft-qa-answer})

(def ^:private deadline-exempt-ops
  "Ops that may proceed on a matter with a lapsed critical deadline, because
  they are how a practice *responds* to one. `:withdraw-representation` is
  here for the same reason: 辞任 may be the only remaining correct act.

  The two recording ops are here on a different argument: refusing to write
  down an arriving fax or a client's question because the matter is already
  in breach destroys the evidence of what the practice was told and when."
  #{:run-conflict-check :remediate-deadline :withdraw-representation
    :file-with-court :record-time-entry
    :record-inbound-transmission :record-qa-question :confirm-transmission})

(def prose-keys
  "Keys whose presence on an intake, an arrival or a question means the text
  itself is about to enter the record. `lawfirm.intake` explains why it must
  not: at the front door there is no privilege yet to protect it with, and a
  record layer that holds the prose is a record layer that leaks it."
  #{:body :text :content :summary :message :prose})

(defn- prose-violations [label m]
  (let [found (filter #(some? (get m %)) prose-keys)]
    (when (seq found)
      [{:rule :prose-in-record
        :detail (str label "に本文が含まれている（" (pr-str (vec found))
                     "）。記録に載せてよいのは分類と digest のみで、"
                     "本文はこの記録層が到達しない場所に置く")}])))

(defn- conflict-required? [op] (not= :run-conflict-check op))

(defn- hard-violations
  [store {:keys [request proposal context]} me c m]
  (let [{:keys [op billable-hours doc-id grant trust-entry
                transmission recipient qa-question answer-id
                transmission-confirmation]} proposal
        today (:today context)
        billable? (contains? billable-ops op)
        matter? (contains? matter-scoped-ops op)
        live (when (and m (conflict-required? op)) (conflict/screen-matter store m))
        breached (when (and m (not (contains? deadline-exempt-ops op)))
                   (deadline/breached-critical store (:matter-id m) today))
        wp (when doc-id (store/work-product store doc-id))
        reviewer (when (= :review-work-product op)
                   (store/bengoshi store (or (:reviewed-by proposal) (:bengoshi-id request))))]
    ;; Invariants 1 and 3 are the fleet-shared provenance rules, taken from
    ;; `kotoba-lang/governor` rather than hand-copied. Their logic is identical
    ;; in 376 repositories here and one of those copies had drifted
    ;; (ADR-2607309100); the `:detail` wording stays this repo's own, because
    ;; it is text a 弁護士 reads. Everything from invariant 2 down is this
    ;; actor's domain and stays here.
    (cond-> (gov/violations
             (gov/no-actuation
              proposal
              {:detail "effect は :propose のみ許可（本 actor は提出・送金・署名・送付を自ら実行しない）"})
             (partner/verification-violations me today)
             (gov/missing-subject c {:detail "未登録の依頼者"})
             (gov/unknown-scope m {:applies? matter?
                                   :detail "未登録の事件に対する操作は不可"})
             (when matter?
               (gov/scope-owner-mismatch
                m request
                {:detail (str "事件 " (:matter-id m) " は別依頼者のもの")})))

      (and m (conflict-required? op) (not (conflict/cleared-by-human? store (:matter-id m))))
      (conj {:rule :conflict-check-not-cleared
             :detail "署名・日付のある利益相反チェック記録が無い（規程27条・28条は人の義務であってフラグではない）"})

      (and live (not (:cleared? live)))
      (conj {:rule :conflict-hit-live
             :detail (str "本日時点の再スクリーンで利益相反を検出: "
                          (pr-str (mapv :rule (:hits live))))
             :hits (:hits live)})

      (and billable? m (not (seq (str (:fee-agreement m)))))
      (conj {:rule :fee-agreement-missing
             :detail "委任契約書が未登録の事件に有償業務は計上できない（規程30条）"})

      (and billable? m (number? billable-hours)
           (> (+ (store/billed-hours store (:matter-id m)) billable-hours)
              (or (:max-billable-hours m) 0)))
      (conj {:rule :engagement-scope-exceeded
             :detail (str "計上済 " (store/billed-hours store (:matter-id m))
                          "h + 今回 " billable-hours "h が受任範囲上限 "
                          (:max-billable-hours m) "h を超過（登録範囲を超える請求は"
                          "スコープクリープであって注意義務ではない）")})

      (seq breached)
      (conj {:rule :critical-deadline-breached
             :detail (str "重要期限が徒過している: "
                          (pr-str (mapv (juxt :label :due-on) breached))
                          "（期限徒過の放置は弁護過誤。是正・提出・辞任のいずれかを先に）")})

      (some? trust-entry)
      (into (trust/disbursement-violations store trust-entry))

      (some? grant)
      (into (partner/grant-violations store grant today))

      (and (= :issue-work-product op) (nil? wp))
      (conj {:rule :unknown-work-product :detail "未登録の書面は発送できない"})

      (and (= :issue-work-product op) wp (not= :lawyer-reviewed (:status wp)))
      (conj {:rule :work-product-not-reviewed
             :detail (str "書面 " doc-id " は status " (pr-str (:status wp))
                          "。弁護士が自ら精査し必要に応じ自ら修正した記録の無い書面は"
                          "外部に出せない（法務省2023年ガイドライン）")})

      (and (= :issue-work-product op) wp (= :lawyer-reviewed (:status wp))
           (not (partner/verified-active? (store/bengoshi store (:reviewed-by wp)) today)))
      (conj {:rule :reviewer-not-counsel
             :detail (str "書面 " doc-id " の精査者 " (pr-str (:reviewed-by wp))
                          " が有効な弁護士登録として確認できない")})

      (and (= :review-work-product op) (not (partner/verified-active? reviewer today)))
      (conj {:rule :reviewer-not-counsel
             :detail "書面の精査は有効登録の弁護士のみが行える"})

      (and (:on-behalf-of-grant request) m
           (not (store/active-grant? (store/counsel-grant store (:on-behalf-of-grant request))
                                     (or (:capability request) :read)
                                     today)))
      (conj {:rule :privilege-boundary
             :detail (str "grant " (pr-str (:on-behalf-of-grant request))
                          " は失効しているか当該 capability を含まない（秘密保持義務）")})

      ;; 14 — 送達. The destination comes from the record or it does not
      ;; happen. Checked whenever an outbound transmission is proposed, not
      ;; only for `:transmit-work-product`, so a future op cannot route around
      ;; it by carrying a transmission under a different name.
      (= :outbound (:direction transmission))
      (into (transmission/outbound-violations store transmission today))

      ;; 15 — a 送達先 is only as good as the person who checked it.
      (= :register-recipient op)
      (into (for [[ch coord] (:channels recipient)
                  :when (not (partner/verified-active?
                              (store/bengoshi store (:verified-by coord)) today))]
              {:rule :recipient-verifier-not-counsel
               :detail (str (get transmission/channel-labels ch (name ch))
                            " の宛先確認者 " (pr-str (:verified-by coord))
                            " が有効な弁護士登録として確認できない")}))

      ;; 16 — a 相談回答 is 法律事務, and travels the same ladder as a 書面.
      (= :send-qa-answer op)
      (into (qa/send-violations store answer-id today))

      ;; 17 — and only a 弁護士 may record having examined one.
      (and (= :review-qa-answer op)
           (not (partner/verified-active?
                 (store/bengoshi store (or (:reviewed-by proposal)
                                           (:bengoshi-id request)))
                 today)))
      (conj {:rule :qa-reviewer-not-counsel
             :detail "回答の精査は有効登録の弁護士のみが行える"})

      ;; 18 — the no-prose promise, enforced rather than agreed to.
      (some? transmission)
      (into (prose-violations "受信記録" transmission))

      (some? qa-question)
      (into (prose-violations "質問の記録" qa-question))

      ;; 19 — a 送達 outcome may be recorded, and a misdirection is recorded
      ;; rather than refused: the document has already left, and refusing to
      ;; write down where it went would destroy the evidence of the incident.
      (= :confirm-transmission op)
      (into (transmission/confirmation-violations
             transmission-confirmation
             (->> (store/transmissions-of store (:matter-id transmission-confirmation))
                  (filter #(= (:transmission-id transmission-confirmation)
                              (:transmission-id %)))
                  first))))))

(defn check
  "Assess `proposal` against the registered record. Pure: never mutates the
  store, never files, never pays, never discloses.

  Returns `{:ok? :hard? :escalate? :violations :confidence :screen}`."
  [request context proposal store]
  (let [c (store/client store (:client-id request))
        m (some->> (or (:matter-id proposal) (:matter-id request)) (store/matter store))
        me (store/bengoshi store (:bengoshi-id request))
        hard (hard-violations store
                              {:request request :context context :proposal proposal}
                              me c m)
        ]
    (gov/verdict
     {:violations hard
      :confidence (:confidence proposal)
      :confidence-floor confidence-floor
      :escalating-op? (contains? always-escalate-ops (:op proposal))
      ;; The screen the gate actually ran, retained so a console can show the
      ;; 弁護士 what was checked rather than only the conclusion.
      :extra {:screen (when m (conflict/screen-matter store m))}})))

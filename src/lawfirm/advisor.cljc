(ns lawfirm.advisor
  "LawFirmAdvisor — the single node where a language model is allowed to
  exist, and the only thing it is allowed to produce is a *proposal*.

  Sealing the model into one node is what makes the rest of the repo
  reviewable. The advisor has no store writes, no network authority of its
  own, no way to reach a court or a payment rail. It reads a request and
  returns a map. `lawfirm.governor` then checks that map against the
  registered record — 名簿, 委任契約, 利益相反, 受任範囲, 預り金, 期限 —
  independently, without reading the advisor's rationale. An advisor that
  hallucinates a limitation period or a cleared conflict cannot turn that into
  an act, because nothing downstream trusts it.

  This is also why the advisor is the *drafting* surface and never the
  *reviewing* one: 法務省 2023年ガイドライン treats a machine-assembled
  document as lawful precisely when a 弁護士 「自ら精査し、必要に応じ自ら修正
  する」. The reviewer is a person, checked by
  `lawfirm.governor` invariant 11.

  ## Model selection

  Per CLAUDE.md (ADR-2607173100) no concrete model id is baked in. Resolution
  order is: explicit `:model` option → the fleet alias `murakumo-main` → the
  endpoint alone (whatever that endpoint currently serves). A hardcoded model
  name would pin this practice to a model generation and silently rot.

  Proposal shape:

    {:op :effect :propose :matter-id :billable-hours :doc-id :grant
     :trust-entry :invoice-id :stake :confidence :rationale}"
  (:require [clojure.string :as str]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(def fleet-alias
  "The fleet's main-model alias entry. Switching models is a PUT on this one
  KV entry; every consumer follows on its next call."
  {:alias "murakumo-main"
   :registry "https://api.murakumo.cloud/infer/models/murakumo-main"
   :endpoint "https://api.murakumo.cloud/v1/messages"})

(defn resolve-model
  "Resolve the model name to send. `opts` may carry `:model` (explicit
  override) or `:resolve-alias-fn` (a 0-arg fn returning the alias entry).
  Falls back to the alias *name*, which the worker resolves server-side —
  never to a concrete model id."
  [{:keys [model resolve-alias-fn]}]
  (or model
      (when (fn? resolve-alias-fn)
        (some-> (resolve-alias-fn) :alias-for))
      (:alias fleet-alias)))

;; ---------------------------------------------------------------------------
;; Deterministic advisor
;; ---------------------------------------------------------------------------

(def ^:private stake-confidence
  {:high 0.7 :medium 0.85 :low 0.95})

(def payload-keys
  "Keys carried verbatim from the request onto the proposal, never authored by
  a model.

  This is a safety boundary, not a plumbing convenience. A trust movement, a
  co-counsel grant, a conflict-clearance record and a document identity are
  *facts supplied by the practice*; a model that could author them could
  author a ¥3,000,000 disbursement or a grant to a lawyer who never agreed to
  anything, and the governor would then be checking a fabrication against the
  record rather than checking the practice's own instruction. The model gets
  to choose the `:op`, the hours, the confidence and the rationale — the
  things a judgement is made of — and nothing that moves money or opens a
  file.

  `:transmission` and `:recipient` are here for the sharpest version of the
  same argument. `lawfirm.transmission` removes the destination from send time
  so it cannot be mistyped; a model that could author `:recipient` would put
  it back, and the number it invented would be checked by nothing — the
  governor validates the destination *against the registered recipient*, and a
  fabricated recipient is a fabricated check."
  [:matter-id :doc-id :deadline-id :invoice-id :question-id :answer-id
   :work-product :time-entry :trust-entry :invoice :grant :conflict-check
   :transmission :transmission-confirmation :recipient :qa-question :qa-answer
   :reviewed-by :reviewed-on :sent-on])

(defn- carry-payload
  "Overlay the request's payload facts onto a proposal."
  [proposal request]
  (merge proposal (into {} (filter (comp some? val)) (select-keys request payload-keys))))

(defn- infer [_store {:keys [op stake billable-hours client-id] :as request}]
  (let [stake (or stake :low)]
    (-> (cond-> {:op op
                 :effect :propose
                 :stake stake
                 :confidence (get stake-confidence stake 0.5)
                 :rationale (str "proposed " (name (or op :unknown))
                                 " for client " client-id)}
          (some? billable-hours) (assoc :billable-hours billable-hours))
        (carry-payload request))))

(defn mock-advisor
  "Deterministic advisor: echoes the request as a proposal. Used by the tests
  and by `lawfirm.render-html`, so the governor's behaviour is demonstrated
  without a model in the loop."
  []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

;; ---------------------------------------------------------------------------
;; LLM advisor
;; ---------------------------------------------------------------------------

(def system-prompt
  (str/join
   "\n"
   ["あなたは日本の法律事務所の業務支援アドバイザーです。"
    "与えられた業務リクエストに対し、EDN の map を1つだけ返してください。"
    "キー: :op :matter-id :billable-hours :stake :confidence :rationale"
    ""
    "厳守事項:"
    "- あなたは提案しかできません。提出・送金・署名・送付は一切行えません。"
    "- 受任範囲（:max-billable-hours）を超える工数を提案してはなりません。"
    "- 利益相反チェック未了の事件に業務を提案してはなりません。"
    "- 受任・裁判所への提出・預り金の出金・和解・辞任・書面の外部送付・"
    "  共同受任の招請は、確信度に関わらず必ず弁護士の承認を要します。"
    "- 法令・判例・期限を推測で述べないこと。不明な場合は :confidence を下げ、"
    "  :rationale に「不明」と明記すること。捏造は最も重い失敗です。"
    ""
    "返答は EDN の map 1つだけ。説明文を付けないこと。"]))

(defn- unparseable [why]
  {:op :unknown :effect :propose :confidence 0.0 :stake :high
   :rationale (str "LLM response " why)})

(defn parse-proposal
  "Parse an EDN proposal out of a model response. A response we cannot read
  becomes a zero-confidence proposal, which the governor escalates — never a
  silently dropped or guessed-at one."
  [content]
  (try
    (let [p (edn/read-string (str content))]
      (if (map? p)
        (assoc p :effect :propose)
        (unparseable "was not a map")))
    (catch #?(:clj Exception :cljs :default) _
      (unparseable "could not be parsed as EDN"))))

(defn llm-advisor
  "Advisor backed by a chat model. `generate-fn` is
  `(fn [model messages opts] {:content str})` — injected, so this namespace
  holds no network authority and the tests need no model."
  ([generate-fn] (llm-advisor generate-fn {}))
  ([generate-fn opts]
   (reify Advisor
     (-advise [_ _store request]
       (let [model (resolve-model opts)
             msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "業務リクエスト: " (pr-str request))}]]
         ;; `carry-payload` last: whatever the model returned for a payload key
         ;; is discarded in favour of the practice's own record. See
         ;; `payload-keys`.
         (-> (parse-proposal (:content (generate-fn model msgs opts)))
             (carry-payload request)))))))

package skillbill.infrastructure.sqlite.review.stage

internal val reviewSummarySql =
  """
  SELECT
    review_run_id,
    review_session_id,
    routed_skill,
    detected_scope,
    detected_stack,
    execution_mode,
    routed_skill_canonical,
    detected_stack_canonical,
    detected_scope_canonical,
    detected_scope_detail,
    specialist_reviews,
    review_finished_at,
    review_finished_event_emitted_at,
    orchestrated_run
  FROM review_runs
  WHERE review_run_id = ?
  """.trimIndent()

internal val importedFindingsSql =
  """
  SELECT finding_id, severity, confidence, issue_category, location, description, finding_text, lane_skill_name
  FROM findings
  WHERE review_run_id = ?
  ORDER BY finding_id
  """.trimIndent()

internal val reviewRunLanesSql =
  """
  SELECT
    lane_skill_name,
    pack_slug,
    area,
    depth,
    required,
    order_index,
    origin_layer_chain,
    resolution_state,
    review_disposition,
    bundle_composition_digest,
    segment_accounting_json,
    unreviewed_segment_ids,
    budget_dimension
  FROM review_run_lanes
  WHERE review_run_id = ?
  ORDER BY order_index, lane_skill_name
  """.trimIndent()

internal val laneEffectivenessSql =
  """
  WITH latest_feedback AS (
    SELECT review_run_id, finding_id, MAX(id) AS latest_id
    FROM feedback_events
    GROUP BY review_run_id, finding_id
  )
  SELECT
    r.routed_skill_canonical AS routed_skill_canonical,
    COALESCE(l.pack_slug, f.lane_pack_slug) AS pack_slug,
    COALESCE(l.area, f.lane_area) AS area,
    COALESCE(fe.event_type, '') AS outcome_type
  FROM findings f
  JOIN review_runs r ON r.review_run_id = f.review_run_id
  LEFT JOIN review_run_lanes l
    ON l.review_run_id = f.review_run_id AND l.lane_skill_name = f.lane_skill_name
  LEFT JOIN latest_feedback lf
    ON lf.review_run_id = f.review_run_id AND lf.finding_id = f.finding_id
  LEFT JOIN feedback_events fe
    ON fe.id = lf.latest_id
  WHERE (? IS NULL OR f.review_run_id = ?)
  """.trimIndent()

internal val findingMetadataSql =
  """
  SELECT finding_id, severity, confidence
  FROM findings
  WHERE review_run_id = ? AND finding_id = ?
  """.trimIndent()

internal val numberedFindingsSql =
  """
  SELECT
    f.finding_id,
    f.severity,
    f.confidence,
    f.location,
    f.description,
    COALESCE(fl.lane_skill_name, f.lane_skill_name) AS lane_skill_name,
    COALESCE(v.claim_verdict, a.claim_verdict) AS claim_verdict,
    a.scope_disposition AS scope_disposition,
    COALESCE(NULLIF(a.citations, ''), v.citations) AS citations,
    COALESCE(a.severity_adjustment_direction, v.severity_adjustment_direction) AS severity_adjustment_direction,
    COALESCE(a.severity_adjustment_justification,
      v.severity_adjustment_justification) AS severity_adjustment_justification
  FROM findings f
  LEFT JOIN review_run_finding_lanes fl
    ON fl.review_run_id = f.review_run_id AND fl.finding_id = f.finding_id
  LEFT JOIN review_run_finding_verdicts v
    ON v.review_run_id = f.review_run_id AND v.finding_id = f.finding_id AND v.stage = 'verification'
  LEFT JOIN review_run_finding_verdicts a
    ON a.review_run_id = f.review_run_id AND a.finding_id = f.finding_id AND a.stage = 'adjudication'
  WHERE f.review_run_id = ?
  ORDER BY f.finding_id
  """.trimIndent()

internal val findingStatusSourcesSql =
  """
  SELECT
    f.finding_id,
    (
      SELECT e.event_type FROM feedback_events e
      WHERE e.review_run_id = f.review_run_id AND e.finding_id = f.finding_id
        AND e.event_type IN ('fix_applied', 'fix_rejected', 'false_positive', 'finding_accepted')
      ORDER BY e.id DESC LIMIT 1
    ) AS feedback_event,
    (
      SELECT o.outcome FROM review_finding_outcomes o
      WHERE o.review_run_id = f.review_run_id AND o.finding_id = f.finding_id
      ORDER BY o.review_pass_number DESC, o.recorded_at DESC LIMIT 1
    ) AS loop_outcome
  FROM findings f
  WHERE f.review_run_id = ?
  """.trimIndent()

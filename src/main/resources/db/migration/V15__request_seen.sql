-- ============================================================================
-- V15 — "You have not seen this yet", for the person who ASKED
--
-- The leaver already has a badge: count the PENDING requests against their
-- listings and the number falls to zero as they answer them. The status field
-- doubles as the "still needs you" marker, so nothing extra was needed.
--
-- The requester has no such field. "Requests I sent that were accepted" is a
-- count that only ever goes UP — the status never moves again once it is
-- CONNECTED — so a badge built on it would be permanent. A badge that is
-- always lit is one people stop seeing, which is exactly the outcome the
-- leaver's badge exists to avoid.
--
-- "New" needs a "seen", and there is no way around that. One nullable
-- timestamp, set when the requester opens the page where the answer is shown.
-- ============================================================================

ALTER TABLE interest_requests
    ADD COLUMN requester_seen_at TIMESTAMPTZ;


-- Rows that already exist were answered before this column did, so nobody can
-- meaningfully be told they are new. Marking them seen at their response time
-- keeps the first render honest rather than announcing a month of old news.
--
-- COALESCE because responded_at is null on a request nobody has answered; such
-- a row is not news either way, and a null seen_at on it is the correct state.
UPDATE interest_requests
   SET requester_seen_at = COALESCE(responded_at, updated_at)
 WHERE status <> 'PENDING';


-- The count behind the badge: "answered, and I have not looked".
--
-- Partial, because the rows that satisfy it are a small and shrinking
-- minority — every visit to the requests page empties it again. Indexing all
-- of interest_requests to find them would be paying for the whole table to
-- answer a question about a handful of rows.
CREATE INDEX interest_requests_unseen_ix
    ON interest_requests (requester_user_id)
    WHERE requester_seen_at IS NULL AND status IN ('CONNECTED', 'DECLINED');


COMMENT ON COLUMN interest_requests.requester_seen_at IS
    'When the REQUESTER last opened the page showing this answer. Null means unread news. Nothing to do with the leaver, who reads state from status.';

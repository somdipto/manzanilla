"""Publication gates exercise values read from APKs, without signing or contacting services."""
from dataclasses import replace

import pytest

from android.release_identity import Identity, semver, validate


PREVIOUS = Identity("dev.orange.hotel", "17.0.4", 34, frozenset({"original-signer"}))
CANDIDATE = replace(PREVIOUS, name="17.0.5", code=35)


def test_explicit_candidate_supersedes_installed_release_without_run_counters():
    validate(CANDIDATE, PREVIOUS, "17.0.5", 35, "v17.0.5")


@pytest.mark.parametrize("candidate,previous,name,code,tag", [
    (CANDIDATE, PREVIOUS, "17.0.5", 35, "v700"),
    (CANDIDATE, PREVIOUS, "17.0.6", 35, "v17.0.6"),
    (CANDIDATE, PREVIOUS, "17.0.5", 36, "v17.0.5"),
    (replace(CANDIDATE, code=34), PREVIOUS, "17.0.5", 34, "v17.0.5"),
    (CANDIDATE, replace(PREVIOUS, code=36), "17.0.5", 35, "v17.0.5"),
    (CANDIDATE, replace(PREVIOUS, name="17.0.6"), "17.0.5", 35, "v17.0.5"),
    (replace(CANDIDATE, package="other.package"), PREVIOUS, "17.0.5", 35, "v17.0.5"),
    (replace(CANDIDATE, signers=frozenset({"new-key"})), PREVIOUS, "17.0.5", 35, "v17.0.5"),
    (replace(CANDIDATE, signers=frozenset()), PREVIOUS, "17.0.5", 35, "v17.0.5"),
])
def test_tag_apk_mismatch_downgrade_and_signer_replacement_block_publication(candidate, previous, name, code, tag):
    with pytest.raises(ValueError):
        validate(candidate, previous, name, code, tag)


@pytest.mark.parametrize("name", ["700", "17.00.5", "17.0.5-rc.1", "v17.0.5", "17.0.5garbage"])
def test_publication_accepts_only_explicit_stable_semantic_versions(name):
    with pytest.raises(ValueError):
        semver(name)

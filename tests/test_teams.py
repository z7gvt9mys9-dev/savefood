from backend.utils import JOIN_CODE_ALPHABET, JOIN_CODE_LENGTH, generate_join_code


def test_code_length_and_alphabet():
    code = generate_join_code()
    assert len(code) == JOIN_CODE_LENGTH
    assert all(ch in JOIN_CODE_ALPHABET for ch in code)


def test_no_ambiguous_characters():
    # 0/O and 1/I/L are excluded so codes survive being dictated aloud.
    for ch in "0O1IL":
        assert ch not in JOIN_CODE_ALPHABET


def test_codes_vary():
    codes = {generate_join_code() for _ in range(200)}
    assert len(codes) > 150  # collisions this dense would mean broken randomness

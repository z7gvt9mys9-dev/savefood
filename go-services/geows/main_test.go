package main

import (
	"math"
	"testing"

	"github.com/golang-jwt/jwt/v5"
)

func TestParseTokenAcceptsOnlyHS256(t *testing.T) {
	secretKey = []byte("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
	claims := jwt.MapClaims{"sub": "alice", "role": "volunteer", "related_id": 7}

	hs256, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(secretKey)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := parseToken(hs256)
	if err != nil || parsed.Sub != "alice" || parsed.Role != "volunteer" || parsed.RelatedID == nil || *parsed.RelatedID != 7 {
		t.Fatalf("HS256 token was not parsed correctly: claims=%+v err=%v", parsed, err)
	}

	hs512, err := jwt.NewWithClaims(jwt.SigningMethodHS512, claims).SignedString(secretKey)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := parseToken(hs512); err == nil {
		t.Fatal("HS512 token must be rejected")
	}
}

func TestValidCoordinatesRejectsWrappedAndNonFiniteValues(t *testing.T) {
	for _, c := range []struct {
		lat, lon float64
		want     bool
	}{
		{43.238, 76.889, true},
		{90, 180, true},
		{91, 76, false},
		{43, 181, false},
		{360, 76, false},
		{math.NaN(), 76, false},
	} {
		if got := validCoordinates(c.lat, c.lon); got != c.want {
			t.Errorf("validCoordinates(%v, %v) = %v, want %v", c.lat, c.lon, got, c.want)
		}
	}
}

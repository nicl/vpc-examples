package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"

	"golang.org/x/exp/slices"
)

// Structs are the basic data type in Go - a bit like 'case classes' but also
// quite different! The `json:..` annotations indicate the field to use when
// (de)serialising to JSON. Note, in Go, 'marshal' and 'unmarshal' are used
// instead of 'serialise' and 'deserialise' (aka 'write' and 'read').
type PrismVPC struct {
	VPCID     string        `json:"vpcId"`
	AccountID string        `json:"accountId"`
	IsDefault bool          `json:"default"`
	Subnets   []PrismSubnet `json:"subnets"`
}

type PrismSubnet struct {
	IsPublic bool   `json:"isPublic"`
	SubnetID string `json:"subnetId"`
}

type PrismAccount struct {
	AccountNumber string `json:"accountNumber"`
	AccountName   string `json:"accountName"`
}

type PrismResponseAccountsWrapper struct {
	Data []PrismAccount `json:"data"`
}

type PrismVPCs struct {
	VPCs []PrismVPC `json:"vpcs"`
}

type PrismResponseVPCsWrapper struct {
	Data struct {
		VPCs []PrismVPC `json:"vpcs"`
	} `json:"data"`
}

// Internal models

type Logging struct {
	StreamName string
}

type AccountInfo struct {
	AccountNumber          string
	AccountName            string
	Stack                  string
	BucketForArtifact      *string
	BucketForPrivateConfig *string
	Logging                Logging
	VPCs                   []PrismVPC
}

// Go doesn't have Options, so often used a second bool ('ok') return value to
// indicate if found or not.
func findPrimaryVPC(VPCs []PrismVPC) (PrismVPC, bool) {
	i := slices.IndexFunc(VPCs, func(vpc PrismVPC) bool {
		var publicSubnets, privateSubnets []PrismSubnet
		for _, subnet := range vpc.Subnets {
			if subnet.IsPublic {
				publicSubnets = append(publicSubnets, subnet)
			} else {
				privateSubnets = append(privateSubnets, subnet)
			}
		}

		return !vpc.IsDefault && len(publicSubnets) == 3 && len(privateSubnets) == 3
	})

	if i == -1 {
		return PrismVPC{}, false
	}

	return VPCs[i], true
}

func subnetsAsTypescriptArray(subnets []PrismSubnet) string {
	ids := []string{}
	for _, s := range subnets {
		ids = append(ids, fmt.Sprintf("'%s'", s.SubnetID))
	}

	return "[" + strings.Join(ids, ", ") + "]"
}

func publicSubnets(subnets []PrismSubnet) []PrismSubnet {
	out := []PrismSubnet{}

	for _, subnet := range subnets {
		if subnet.IsPublic {
			out = append(out, subnet)
		}
	}

	return out
}

func privateSubnets(subnets []PrismSubnet) []PrismSubnet {
	out := []PrismSubnet{}

	for _, subnet := range subnets {
		if !subnet.IsPublic {
			out = append(out, subnet)
		}
	}

	return out
}

// Go does not have string interpolation sadly so this is more painful and
// harder to read than the Scala equivalent.
func (info AccountInfo) asTypescriptTemplate() string {
	primaryVPC, ok := findPrimaryVPC(info.VPCs)

	vpc := "// No suitable VPC found."
	if ok {
		public := publicSubnets(primaryVPC.Subnets)
		private := privateSubnets(primaryVPC.Subnets)

		vpc = fmt.Sprintf(`vpc: {
    primary: {
        privateSubnets: %v
        publicSubnets: %v
    }
}`, subnetsAsTypescriptArray(private), subnetsAsTypescriptArray(public))
	}

	return fmt.Sprintf(`import type { AwsAccountSetupProps } from '../types';

export const %sAccount: AwsAccountSetupProps = {
    accountNumber: '%s',
    accountName: '%s',
    stack: '%s',
    bucketForArtifacts: 'TODO',
    bucketForPrivateConfig: 'TODO',
    logging: {
    streamName: 'TODO',
    %s
}
`, camelCase(info.AccountName), info.AccountNumber, info.AccountName, camelCase(info.AccountName), vpc)
}

type AccountID string

// A bit like the Scala equivalent trait.
type PrismLike interface {
	getAccounts() []PrismAccount
	getVPCs() map[AccountID][]PrismVPC
}

type Prism struct{}

// 'Methods' in Go look like this.
func (p Prism) getAccounts() []PrismAccount {
	// Use the in-built 'http' library, which you quickly get to know when
	// writing Go.
	resp, err := http.Get("https://prism.gutools.co.uk/sources/accounts")
	check(err, "unable to get prism accounts")
	defer resp.Body.Close()

	data, err := io.ReadAll(resp.Body)
	check(err, "unable to read prism accounts response body")

	var wrapper PrismResponseAccountsWrapper

	// Use the in-build 'json' library here, which you quickly get to know when
	// writing Go.
	err = json.Unmarshal(data, &wrapper)
	check(err, "unable to unmarshal accounts response")

	return wrapper.Data
}

// Go typically does not provide these kinds of collection functions out of the
// box so you have to write them yourself or use a library :(.
func groupBy[A any, B comparable](items []A, f func(item A) B) map[B][]A {
	m := make(map[B][]A)

	for _, item := range items {
		key := f(item)
		existing, ok := m[key]
		if !ok {
			m[key] = []A{item}
		} else {
			m[key] = append(existing, item)
		}
	}

	return m
}

func (p Prism) getVPCs() map[AccountID][]PrismVPC {
	resp, err := http.Get("https://prism.gutools.co.uk/vpcs")
	check(err, "unable to get prism vpcs")
	defer resp.Body.Close()

	data, err := io.ReadAll(resp.Body)
	check(err, "unable to read prism vpcs response body")

	var wrapper PrismResponseVPCsWrapper
	err = json.Unmarshal(data, &wrapper)
	check(err, "unable to unmarshal vpcs response")

	return groupBy(wrapper.Data.VPCs, func(item PrismVPC) AccountID {
		return AccountID(item.AccountID)
	})
}

// Another way of denoting a string that is present or not is to use a 'pointer'
// in Go. Again, Scala is a bit nicer here.
func stringPtr(s string) *string {
	return &s
}

func check(err error, msg string) {
	if err != nil {
		log.Fatalf("%s: %v", msg, err)
	}
}

func camelCase(s string) string {
	parts := strings.Split(s, "-")

	out := ""
	for _, part := range parts {
		out += strings.Title(part)
	}

	return out
}

// Main is surprisingly similar to the Scala equivalent.
func main() {
	// get accounts and vpcs
	prism := Prism{}
	accounts := prism.getAccounts()
	vpcs := prism.getVPCs()

	accountsToMigrate := []string{"deploy-tools"}

	infos := []AccountInfo{}
	for _, account := range accounts {
		if !slices.Contains(accountsToMigrate, account.AccountName) {
			continue
		}

		vpcs, ok := vpcs[AccountID(account.AccountNumber)]
		if !ok {
			vpcs = []PrismVPC{}
		}

		info := AccountInfo{
			AccountNumber:          account.AccountNumber,
			AccountName:            account.AccountName,
			Stack:                  "TODO",
			BucketForArtifact:      stringPtr("TODO"),
			BucketForPrivateConfig: stringPtr("TODO"),
			Logging:                Logging{StreamName: "TODO"},
			VPCs:                   vpcs,
		}

		infos = append(infos, info)
	}

	for _, info := range infos {
		fmt.Println(info.asTypescriptTemplate())
	}
}

#!/usr/bin/env python3
"""Reference single-package carton selection for the CUST01 sample data.

Usage: python3 cartonize_check.py <sample-dir> > expected_cartonization.csv
Mirrors the rules in README.md so test results from SCALE can be compared line by line.
"""
import csv, math, sys, collections
d=sys.argv[1]
C=list(csv.DictReader(open(d+'/carton_types.csv')))
I={r['item']:r for r in csv.DictReader(open(d+'/item_master.csv'))}
O=collections.OrderedDict()
for r in csv.DictReader(open(d+'/orders.csv')): O.setdefault(r['erp_order'],[]).append(r)
f=float
def dims(x,p=''): return sorted([f(x[p+'length_in']),f(x[p+'width_in']),f(x[p+'height_in'])],reverse=True)
def billable(c,wt):
    l,w,h=f(c['ship_length_in']),f(c['ship_width_in']),f(c['ship_height_in'])
    return max(math.ceil(wt), math.ceil(l*w*h/139))
def pick(lines):
    cube=sum(int(l['qty'])*math.prod(dims(I[l['item']])) for l in lines)
    wt=sum(int(l['qty'])*f(I[l['item']]['weight_lb']) for l in lines)
    poly=all(I[l['item']]['packaging_class']=='POLY_OK' for l in lines)
    for c in sorted(C,key=lambda c:int(c['select_priority'])):
        if c['packaging_class']=='POLY' and not poly: continue
        cd=dims(c,'inside_')
        if not all(all(a<=b for a,b in zip(dims(I[l['item']]),cd)) for l in lines): continue
        cap=math.prod(cd)*f(c['max_fill_pct'])/100
        gw=wt+f(c['tare_weight_lb'])
        if cube<=cap and gw<=f(c['max_weight_lb']):
            return c,cube,cap,wt,gw
    return None,cube,None,wt,None
w=csv.writer(sys.stdout)
w.writerow(['erp_order','line_count','unit_count','total_item_cube_in3','total_item_weight_lb','selected_carton','packaging_class','carton_usable_cube_in3','fill_pct','gross_weight_lb','dim_weight_lb','billable_weight_lb','baseline_packages_one_per_line','baseline_billable_weight_lb','result'])
for o,lines in O.items():
    c,cube,cap,wt,gw=pick(lines)
    # baseline: each order line packed alone (typical "no cartonization" behaviour)
    bp=bb=0
    for l in lines:
        bc,_,_,_,bg=pick([l])
        if bc: bp+=1; bb+=billable(bc,bg)
    units=sum(int(l['qty']) for l in lines)
    if c:
        dw=math.ceil(f(c['ship_length_in'])*f(c['ship_width_in'])*f(c['ship_height_in'])/139)
        w.writerow([o,len(lines),units,round(cube,1),round(wt,2),c['carton_type'],c['packaging_class'],round(cap,1),round(100*cube/cap,1),round(gw,2),dw,billable(c,gw),bp,bb,'SINGLE_PACKAGE'])
    else:
        w.writerow([o,len(lines),units,round(cube,1),round(wt,2),'','','','','','','',bp,bb if bp==len(lines) else '','EXCEPTION_NO_SINGLE_CARTON_FITS'])
